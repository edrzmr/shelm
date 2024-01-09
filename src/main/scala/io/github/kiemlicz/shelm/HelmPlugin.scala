package io.github.kiemlicz.shelm

import io.circe.syntax.EncoderOps
import io.circe.{Json, yaml}
import io.github.kiemlicz.shelm.ChartRepositoryAuth.{Bearer, Cert, NoAuth, UserPassword}
import io.github.kiemlicz.shelm.ChartSettings.{ChartYaml, DependenciesPath, ValuesYaml}
import io.github.kiemlicz.shelm.exception.*
import sbt.Keys.*
import sbt.librarymanagement.PublishConfiguration
import sbt.{Def, ModuleDescriptorConfiguration, ModuleID, Resolver, UpdateLogging, *}

import java.io.{File, FileReader}
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, SECONDS}

object HelmPlugin extends AutoPlugin {
  override def trigger = noTrigger

  object SbtTags {
    val Prepare = sbt.Tags.Tag("prepare")
    val Lint = sbt.Tags.Tag("lint")
    val Package = sbt.Tags.Tag("package")
  }

  object autoImport {
    val Helm: Configuration = config("helm")

    lazy val repositories = settingKey[Seq[ChartRepo]]("Additional Repositories settings") // helm repo add or login
    lazy val shouldUpdateRepositories = settingKey[Boolean]("Perform `helm repo update` at the helm:prepare beginning")
    lazy val chartSettings = settingKey[Seq[ChartSettings]]("All per-Chart settings")
    lazy val downloadedChartsCache = settingKey[File](
      "Directory in which plugin will search for charts before downloading them"
    )
    lazy val chartMuseumClient = settingKey[ChartMuseumClient](
      "JDK HTTP client, supply if different thread pool is to be used"
    )

    lazy val cleanChartsCache = taskKey[Unit]("Cleans charts cache")
    lazy val helmVersion = taskKey[VersionNumber]("Local Helm binary version")
    lazy val setupRegistries = taskKey[Unit]("Setup Helm Repositories. Idempotent operation")
    lazy val updateRepositories = taskKey[Unit]("Update Helm Repositories")
    lazy val chartMappings = taskKey[ChartSettings => ChartMappings]("All per-Chart mappings")
    lazy val prepare = taskKey[Seq[(File, ChartMappings)]](
      "Download Chart if not present locally, copy all includes into Chart directory, return Chart directory"
    )
    lazy val lint = taskKey[Seq[(File, ChartMappings)]]("Lint Helm Chart")
    lazy val packagesBin = taskKey[Seq[PackagedChartInfo]]("Create Helm Charts")

    lazy val baseHelmSettings: Seq[Setting[_]] = Seq(
      repositories := Seq.empty,
      shouldUpdateRepositories := false,
      downloadedChartsCache := new File("helm-cache"),
      chartSettings := Seq.empty[ChartSettings],
      helmVersion := {
        val cmd = "helm version --template {{.Version}}"
        startProcess(cmd) match {
          case HelmProcessResult.Success(output) => VersionNumber(output.stdOut.replaceFirst("^v", ""))
          case HelmProcessResult.Failure(exitCode, output) => throw new HelmCommandException(output, exitCode)
        }
      },
      chartMuseumClient := {
        val h = HttpClient.newBuilder()
          .followRedirects(Redirect.NORMAL)
          .sslContext(SSLContext.getDefault)
          .connectTimeout(Duration.ofSeconds(30))
          .build()
        new ChartMuseumClient(h, Duration.ofSeconds(15))
      },
      cleanChartsCache := {
        val log = streams.value.log
        val cacheBaseDir = downloadedChartsCache.value
        if (cacheBaseDir.exists()) {
          cacheBaseDir.listFiles().foreach(IO.delete)
          log.info("Cache cleaned")
        } else log.info("Cache hasn't been created yet")
      },
      setupRegistries := {
        val log = streams.value.log
        val helmVer = helmVersion.value
        lazy val alreadyAdded = listRepos(log) //todo confirm not evaluated

        repositories.value.filterNot { //todo test it
          case r: LegacyRepo => alreadyAdded.contains((r.name(), r.uri()))
          case _ => false //helm registry login is performed every time, not considering this a problem
        }.foreach {
          case r: IvyCompatibleHttpChartRepository => ensureRepo(r, log)
          case r: ChartMuseumRepository => ensureRepo(r, log)
          case r: OciChartRegistry => loginRepo(r, helmVer, log)
        }
      },
      updateRepositories := {
        val log = streams.value.log
        updateRepo(log)
      },
      chartMappings := { s => ChartMappings(s, target.value) },
      prepare := Def.task {
        val log = streams.value.log
        val helmVer = helmVersion.value
        helmVer match {
          case VersionNumber(Seq(major, _@_*), _, _) if major >= 3 =>
          case _ => sys.error(s"Cannot assert Helm version (must be at least 3.0.0): $helmVer")
        }

        val _ = Def.taskIf {
          if (shouldUpdateRepositories.value) updateRepositories.value
          else ()
        }.value

        val mappings = chartMappings.value //must be outside of lambda
        val settings = chartSettings.value
        settings.map(mappings).zipWithIndex.map { case (mappings, idx) =>
          val tempChartDir = ChartDownloader.download(
            mappings.settings.chartLocation,
            target.value / s"${mappings.settings.chartLocation.chartName.name}-$idx",
            downloadedChartsCache.value,
            log
          )
          val chartYaml = readChart(tempChartDir / ChartYaml)
          val updatedChartYaml = mappings.chartUpdate(chartYaml)
          if (mappings.dependencyUpdate) {
            if (updatedChartYaml.dependencies != chartYaml.dependencies) {
              IO.write(tempChartDir / ChartYaml, yaml.printer.print(updatedChartYaml.asJson))
            }
            updateDependencies(tempChartDir, log)
            (tempChartDir ** "*.tgz").get()
              .foreach { f =>
                ChartDownloader.extractArchive(f.toURI, tempChartDir / DependenciesPath)
                IO.delete(f)
              }
          }
          mappings.includeFiles.foreach { case (src, d) =>
            val dst = tempChartDir / d
            if (src.isDirectory) IO.copyDirectory(src, dst, overwrite = true)
            else IO.copyFile(src, dst)
          }
          mappings.yamlsToMerge.foreach { case (overrides, onto) =>
            val dst = tempChartDir / onto
            if (dst.exists()) {
              val onto = yaml.parser.parse(new FileReader(dst))
              val resultJson = yaml.parser.parseDocuments(new FileReader(overrides)).foldLeft(onto) { (onto, parsed) =>
                parsed.flatMap(parsed => onto.map(onto => onto.deepMerge(parsed)))
              }
              resultJson match {
                case Right(Json.False) | Right(Json.Null) =>
                  log.info(s"Empty YAML for $dst")
                  //otherwise parser returned scalar "null"/"false" which is improper YAML file
                  IO.write(dst, Array.empty[Byte])
                case json =>
                  val result = json.map(yaml.printer.print)
                  IO.write(dst, resultOrThrow(result))
              }
            } else IO.copyFile(overrides, dst)
          }
          val valuesFile = tempChartDir / ValuesYaml
          val valuesJson = if (valuesFile.exists()) yaml.parser.parse(new FileReader(valuesFile)).toOption else None
          val overrides = mergeOverrides(mappings.valueOverrides(valuesJson))
          overrides.foreach { valuesOverride =>
            IO.write(
              valuesFile,
              if (valuesFile.exists())
                resultOrThrow(
                  yaml.parser
                    .parse(new FileReader(valuesFile))
                    .map(onto => yaml.printer.print(onto.deepMerge(valuesOverride)))
                )
              else yaml.printer.print(valuesOverride),
            )
          }
          IO.write(tempChartDir / ChartYaml, yaml.printer.print(updatedChartYaml.asJson))
          cleanFiles ++= Seq(tempChartDir) //todo is it thread safe? After all this can be run concurrently
          (tempChartDir, mappings)
        }
      }.tag(SbtTags.Prepare).value,
      lint := Def.task {
        val log = streams.value.log
        prepare.value.map { case (chartDir, m: ChartMappings) =>
          (lintChart(chartDir, m.fatalLint, log), m)
        }
      }.tag(SbtTags.Lint).value,
      packagesBin := Def.task {
        lint.value.map { case (linted, m: ChartMappings) =>
          val chartYaml = readChart(linted / ChartYaml)
          val location = buildChart(
            linted,
            chartYaml.name,
            chartYaml.version,
            m.destination,
            streams.value.log,
          )
          PackagedChartInfo(chartYaml.name, SemVer2(chartYaml.version), location)
        }
      }.tag(SbtTags.Package).value,
    )
  }

  val random = new SecureRandom

  import autoImport.*

  /**
    * OCI registry only
    * Login to OCI registry
    */
  private[this] def loginRepo(
    registry: OciChartRegistry,
    helmVersion: VersionNumber,
    log: Logger
  ): Unit = {
    helmVersion match {
      case VersionNumber(Seq(major, minor, _@_*), _, _) if major >= 3 && minor >= 8 =>
      case _ => sys.error(s"Cannot login to OCI registry (Helm must be at least in 3.8.0 version): $helmVersion")
    }

    log.info(s"Logging to OCI $registry")
    val loginUri = registry.uri.toString.replaceFirst("^oci://", "")
    val options = chartRepositoryCommandFlags(registry.auth)
    val cmd = s"helm registry login $loginUri $options"
    HelmProcessResult.getOrThrow(startProcess(cmd))
  }

  /**
    * `helm repo add`
    * Doesn't work for OCI
    * https://github.com/helm/helm/issues/10565
    */
  private[this] def ensureRepo(repo: LegacyRepo, log: Logger): Unit = {
    log.info(s"Adding Legacy $repo to Helm Repositories")
    val options = chartRepositoryCommandFlags(repo.auth())
    val cmd = s"helm repo add ${repo.name().name} ${repo.uri()} $options"
    HelmProcessResult.getOrThrow(startProcess(cmd))
  }

  private[this] def updateRepo(log: Logger): Unit = {
    log.info("Updating Helm Repositories")
    HelmProcessResult.getOrThrow(startProcess("helm repo update"))
  }

  private[this] def listRepos(log: Logger): Set[(ChartRepositoryName, URI)] = {
    log.info("Listing Helm Repositories")
    //    val output = HelmProcessResult.getOrThrow(startProcess("helm repo list -o json"))
    //parse
    ???
  }

  private[this] def updateDependencies(chartDir: File, log: Logger): Unit = {
    log.info("Updating Helm Chart's dependencies")
    retrying(s"helm dependency update $chartDir ", log) // due to potential parallel runs...
  }

  private[this] def lintChart(chartDir: File, fatalLint: Boolean, log: Logger): File = {
    log.info("Linting Helm Package")
    val cmd = s"helm lint $chartDir"
    startProcess(cmd) match {
      case HelmProcessResult.Failure(exitCode, output) if fatalLint => throw new HelmCommandException(output, exitCode)
      case _ => chartDir
    }
  }

  private[shelm] def pullChart(options: String, log: Logger): Unit = {
    val cmd = s"helm pull $options"
    retrying(cmd, log)
  }

  private[this] def buildChart(
    chartDir: File,
    chartName: ChartName,
    chartVersion: String,
    targetDir: File,
    log: Logger,
  ): File = {
    val dest = s" -d $targetDir"
    val cmd = s"helm package$dest $chartDir"
    val output = targetDir / s"${chartName.name}-$chartVersion.tgz"
    log.info(s"Creating Helm Package: $cmd")
    retrying(cmd, log)
    output
  }

  /**
    * Retry given command
    * That method exists only due to: https://github.com/helm/helm/issues/2258
    *
    * @param cmd shell command that will potentially be retried
    * @param n   number of tries
    */
  private[shelm] def retrying(cmd: String, sbtLogger: Logger, n: Int = 3): Unit = {
    val initialSleep = FiniteDuration(1, SECONDS)
    val backOff = 2

    @tailrec
    def go(n: Int, result: HelmProcessResult, sleep: FiniteDuration): Unit = result match {
      case HelmProcessResult.Success(output) =>
        if (output.entireOutput.nonEmpty)
          sbtLogger.info(s"Helm command ('$cmd') success, output:\n${output.entireOutput}")
        else
          sbtLogger.info(s"Helm command ('$cmd') success")
      case HelmProcessResult.Failure(exitCode, output) if n > 0 =>
        sbtLogger.warn(
          s"Couldn't perform: $cmd (exit code: $exitCode), failed with: $output, retrying in: $sleep"
        )
        Thread.sleep(sleep.toMillis)
        val nextSleep = (sleep * backOff) + FiniteDuration(random.nextInt() % 1000, TimeUnit.MILLISECONDS)
        go(n - 1, startProcess(cmd), nextSleep)
      case r =>
        sbtLogger.err(s"Couldn't perform: $cmd, retries limit reached")
        HelmProcessResult.getOrThrow(r)
    }

    go(n, startProcess(cmd), initialSleep)
  }

  private[shelm] def startProcess(cmd: String): HelmProcessResult = {
    val logger = new BufferingProcessLogger()
    val exitCode = sys.process.Process(command = cmd) ! logger
    HelmProcessResult(exitCode, logger)
  }

  private[this] def readChart(file: File) = resultOrThrow(
    yaml.parser.parse(new FileReader(file)).flatMap(_.as[Chart])
  )

  private[this] def mergeOverrides(overrides: Seq[Json]): Option[Json] = {
    val merged = overrides.foldLeft(Json.Null)(_.deepMerge(_))
    if (overrides.isEmpty) None else Some(merged)
  }

  private[shelm] def chartRepositoryCommandFlags(settings: ChartRepositoryAuth): String = settings match {
    case NoAuth => ""
    case UserPassword(user, password) => s"--username $user --password $password"
    case Cert(certFile, keyFile, ca) => s"--cert-file ${certFile.getAbsolutePath} --key-file ${
      keyFile.getAbsolutePath
    } ${ca.map(ca => s"--ca-file $ca").getOrElse("")}"
    case Bearer(_) => "" // no appropriate helm option exists
  }

  override lazy val projectSettings: Seq[Setting[_]] =
    inConfig(Helm)(baseHelmSettings)

  override def projectConfigurations: Seq[Configuration] = Seq(Helm)
}

object HelmPublishPlugin extends AutoPlugin {

  object autoImport {

    lazy val outstandingPublishRequests = settingKey[Int](
      "How many publish operations to schedule at given point in time"
    )
    lazy val publishRegistries = settingKey[Seq[ChartRepo]]("Remote registries for publishing all charts")
    lazy val publishChartMuseumConfiguration = taskKey[PublishConfiguration](
      "Configuration for publishing to the ChartMusem."
    ) //.withRank(DTask)
    lazy val publishOCIConfiguration = taskKey[PublishConfiguration](
      "Configuration for publishing to the OCI registry."
    ) //.withRank(DTask)
  }

  import HelmPlugin.autoImport.*
  import autoImport.*


  override def requires: HelmPlugin.type = HelmPlugin

  lazy val baseHelmPublishSettings: Seq[Setting[_]] = Seq(
    artifacts := Seq.empty,
    packagedArtifacts := Map.empty,
    projectID := ModuleID(organization.value, name.value, version.value),
    moduleSettings := ModuleDescriptorConfiguration(projectID.value, projectInfo.value)
      .withScalaModuleInfo(scalaModuleInfo.value),
    ivyModule := {
      val ivy = ivySbt.value
      new ivy.Module(moduleSettings.value)
    },

    outstandingPublishRequests := 1, //careful
    publishRegistries := Seq.empty,
    //publishes into ALL configured repos! if ivy present then setup packagedARtifacts if not but different repo then setup other setting key
    // configured repos determine where to push
    // unable to setup resolvers for OCI support

    publish := Def.taskDyn { // mind: https://github.com/sbt/sbt/issues/6862
      val logger = streams.value.log
      //test this

      Def.sequential( // better than sequential?
        Def.taskIf {
          if (publishRegistries.value.exists(_.isInstanceOf[IvyCompatibleHttpChartRepository])) {
            logger.info("Starting Helm Charts publish to Ivy compatible repository")
            (Helm / publish).value
          } else {
            logger.info("No legacy Ivy-compatible repositories configured for publishing")
          }
        },
        Def.taskIf {
          if (publishRegistries.value.exists(_.isInstanceOf[OciChartRegistry])) {
            logger.info("Starting OCI login")
            setupRegistries.value
          } else {
            logger.info("No OCI registries configured for publishing")
          }
        },
        Def.task {
          logger.info("Starting Helm Charts OCI or CM push")
          /*
          Single Chart is not retried
          First fail causes stop of publish to given repo only (other registries are still tried)
           */
          val errors = publishRegistries.value.map {
            case r: ChartMuseumRepository =>
              chartMuseumClient.value
                .chartMuseumPublishBlocking(r, publishChartMuseumConfiguration.value, logger)
            case OciChartRegistry(uri, _) =>
              sequence(publishOCIConfiguration.value.artifacts.map {
                case (_, file) => pushChart(file, uri, logger)
              }.toList
              ).map(_ => ())
          }.collect {
            case Left(e) => e
          }
          if (errors.nonEmpty) throw new HelmPublishTaskException(errors)
          else logger.info("Chart publishing completed successfully")
        }
      )
    }.value,
    /*
    publishConfiguration is consumed by SBT's `publish` task
    */
    publishConfiguration := {
      PublishConfiguration()
        .withResolverName(Classpaths.getPublishTo(publishTo.value).name)
        .withArtifacts(
          packagedArtifacts.value
            .filter(_ => publishRegistries.value.exists(_.isInstanceOf[IvyCompatibleHttpChartRepository]))
            .toVector
        )
        .withChecksums(checksums.value.toVector) //fixme checksums?
        .withOverwrite(isSnapshot.value)
        .withLogging(UpdateLogging.DownloadOnly)
    },
    publishLocalConfiguration := PublishConfiguration()
      .withResolverName("local")
      .withArtifacts(
        packagedArtifacts.value
          .filter(_ => publishRegistries.value.exists(_.isInstanceOf[IvyCompatibleHttpChartRepository]))
          .toVector
      )
      .withChecksums(checksums.value.toVector)
      .withOverwrite(isSnapshot.value)
      .withLogging(UpdateLogging.DownloadOnly),
    publishM2Configuration := PublishConfiguration()
      .withResolverName(Resolver.mavenLocal.name)
      .withArtifacts(
        packagedArtifacts.value
          .filter(_ => publishRegistries.value.exists(_.isInstanceOf[IvyCompatibleHttpChartRepository]))
          .toVector
      )
      .withChecksums(checksums.value.toVector)
      .withOverwrite(isSnapshot.value)
      .withLogging(UpdateLogging.DownloadOnly),
    publishChartMuseumConfiguration := PublishConfiguration()
      .withArtifacts(
        packagedArtifacts.value.filter(_ => publishRegistries.value.exists(_.isInstanceOf[ChartMuseumRepository]))
          .toVector
      )
      .withChecksums(checksums.value.toVector)
      .withOverwrite(isSnapshot.value)
      .withLogging(UpdateLogging.DownloadOnly),
    publishOCIConfiguration := PublishConfiguration()
      .withArtifacts(
        packagedArtifacts.value.filter(_ => publishRegistries.value.exists(_.isInstanceOf[OciChartRegistry])).toVector
      )
      .withChecksums(checksums.value.toVector)
      .withOverwrite(isSnapshot.value)
      .withLogging(UpdateLogging.DownloadOnly),
  )

  private[shelm] def pushChart(
    chartLocation: File, registryUri: URI, log: Logger
  ): Either[Throwable, Unit] = {
    val cmd = s"helm push $chartLocation $registryUri"
    log.info(s"Publishing Helm Chart: $cmd")
    throwableToLeft(HelmPlugin.retrying(cmd, log, n = 1))
  }

  private[this] def addPackage(
    helmPackageTask: TaskKey[Seq[PackagedChartInfo]],
    extension: String,
    classifier: Option[String] = None,
  ): Seq[Setting[_]] =
    Seq(
      artifacts ++= chartSettings.value.map { s =>
        Artifact( // incomplete since the exact version is unknown
          s.chartLocation.chartName.name,
          extension,
          extension,
          classifier,
          Vector.empty,
          None
        )
      },
      /*
      the `artifacts` is a SettingKey, since the Chart version is known in the Task run, can't set this in settings
       */
      packagedArtifacts ++= artifacts.value
        .zip(helmPackageTask.value)
        .map {
          case (artifact, packagedChart) =>
            artifact.withExtraAttributes(
              artifact.extraAttributes ++ Map(
                "chartVersion" -> packagedChart.version.toString,
                "chartMajor" -> packagedChart.version.major.toString,
                "chartMinor" -> packagedChart.version.minor.toString,
                "chartPatch" -> packagedChart.version.patch.toString,
                "chartName" -> packagedChart.chartName.name,
              )
            ) -> packagedChart.location
        }.toMap,
    )

  /**
    * Saves scoped `publishTo` (`Helm / publishTo)` into `otherResolvers`
    * This way only scoped publishTo is required to be set
    */
  private[this] def addResolver(config: Configuration): Seq[Setting[_]] =
    Seq(otherResolvers ++= (config / publishTo).value.toSeq)

  override lazy val projectSettings: Seq[Setting[_]] =
    inConfig(Helm)(
      Classpaths.ivyPublishSettings
        ++ baseHelmPublishSettings
        ++ addPackage(packagesBin, "tgz")
    ) ++ addResolver(Helm)
}

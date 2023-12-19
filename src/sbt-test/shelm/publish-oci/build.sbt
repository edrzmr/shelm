import _root_.io.circe.{Json, yaml}
import _root_.io.github.kiemlicz.shelm.HelmPlugin.autoImport.Helm
import _root_.io.github.kiemlicz.shelm._

import java.io.FileReader
import java.net.URI

val cn = "cilium"
lazy val assertGeneratedValues = taskKey[Unit]("Assert packageValueOverrides")

lazy val root = (project in file("."))
  .enablePlugins(HelmPlugin, HelmPublishPlugin)
  .settings(
    version := "0.1",
//    resolvers += Resolver.file("local", file("./repo/"))(
//      Patterns("[chartMajor].[chartMinor].[chartPatch]/[artifact]-[chartVersion].[ext]")
//    ),
    // for helm:publishLocal the `local` resolver must be set,
    //    Helm / publishTo := Some(Resolver.file("local", file("/tmp/repo/"))(Patterns("[chartMajor].[chartMinor].[chartPatch]/[artifact]-[chartVersion].[ext]"))),
    Helm / shouldUpdateRepositories := true,
    Helm / repositories := Seq(
      LegacyHttpChartRepository(ChartRepositoryName("stable"), URI.create("https://charts.helm.sh/stable")),
      LegacyHttpChartRepository(ChartRepositoryName("cilium"), URI.create("https://helm.cilium.io/")),
//      OciChartRegistry(URI.create("https://helm.cilium.io/"))
    ),
//    Helm / publishTo := Some(OciResolver()),
    Helm / chartSettings := Seq(
      ChartSettings(
        chartLocation = ChartLocation.AddedRepository(ChartName(cn), ChartRepositoryName("cilium"), Some("1.9.5")),
        metadata = Some(SimpleChartMetadata("a"))
      ),
      ChartSettings(
        chartLocation = ChartLocation.AddedRepository(ChartName(cn), ChartRepositoryName("cilium"), Some("1.9.5")),
        metadata = Some(SimpleChartMetadata("b")))
    ),
    Helm / chartMappings := {
      case s@ChartSettings(ChartLocation.AddedRepository(ChartName(`cn`), ChartRepositoryName("cilium"), Some("1.9.5")), Some(SimpleChartMetadata("a"))) =>
        ChartMappings(
          s,
          destination = target.value,
          chartUpdate = c => c.copy(
            version = "2.1.3+meta1",
          ),
          Seq.empty, Seq.empty, valueOverrides = _ => Seq(
            Json.fromFields(
              Iterable(
                "nameOverride" -> Json.fromString("testNameSalt1"),
              )
            )
          ),
          fatalLint = false,
        )
      case s@ChartSettings(ChartLocation.AddedRepository(ChartName(`cn`), ChartRepositoryName("cilium"), Some("1.9.5")), Some(SimpleChartMetadata("b"))) =>
        ChartMappings(
          s,
          destination = target.value,
          chartUpdate = c => c.copy(
            version = "2.1.3+meta2",
          ),
          Seq.empty, Seq.empty, valueOverrides = _ => Seq(
            Json.fromFields(
              Iterable(
                "nameOverride" -> Json.fromString("testNameSalt2"),
              )
            )
          ),
          fatalLint = false
        )
      case _ => throw new RuntimeException("unexpected")
    }
  )

assertGeneratedValues := {
//TODO
}

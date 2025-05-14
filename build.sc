import mill._, scalalib._
import $file.ext.SpinalHDL.build
import ext.SpinalHDL.build.{core => spinalCore}
import ext.SpinalHDL.build.{lib => spinalLib}
import ext.SpinalHDL.build.{idslplugin => spinalIdslplugin}

val spinalVersion = "1.11.0"
val scalaVer = "2.12.18"

object q extends RootModule with SbtModule{
  def scalaVersion = scalaVer
  def idslPlugin = spinalIdslplugin(scalaVer)
  def ivyDeps = Agg(
    ivy"org.scalatest::scalatest:3.2.17",
    ivy"org.yaml:snakeyaml:1.8",
    ivy"net.fornwall:jelf:0.7.0",
  )
  def moduleDeps = Seq(
    spinalCore(scalaVer),
    spinalLib(scalaVer),
    idslPlugin
  )
  def scalacOptions = super.scalacOptions() ++ idslPlugin.pluginOptions()
}

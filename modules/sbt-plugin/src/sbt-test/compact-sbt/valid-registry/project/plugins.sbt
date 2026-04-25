sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("io.compact" % "compact-sbt" % v)
  case _       => sys.error("plugin.version system property not set")
}

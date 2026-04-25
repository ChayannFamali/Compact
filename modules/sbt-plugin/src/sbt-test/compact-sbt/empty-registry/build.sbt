enablePlugins(CompactPlugin)

// Указываем несуществующую директорию — пустой реестр
compactRegistryPath   := baseDirectory.value / "contracts-empty"
compactFailOnBreaking := true

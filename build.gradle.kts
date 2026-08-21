import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val znConfiguration = providers.gradleProperty("znConfiguration").orElse("Release")
val defaultZnPython = if (
    System.getProperty("os.name").lowercase().contains("windows")
) {
    "python"
} else {
    "python3"
}
val znPython = providers.gradleProperty("znPython").orElse(defaultZnPython)
val znNdkPath = providers.gradleProperty("znNdkPath")
val znCmakePath = providers.gradleProperty("znCmakePath")
val znCommand = buildList {
    add(znPython.get())
    add("miui-home-hyos-zn/build_zn_package.py")
    add("--configuration")
    add(znConfiguration.get())
    if (znNdkPath.isPresent) {
        add("--ndk-path")
        add(znNdkPath.get())
    }
    if (znCmakePath.isPresent) {
        add("--cmake-path")
        add(znCmakePath.get())
    }
}

tasks.register<Exec>("buildZnPackage") {
    group = "native"
    description = "Build the arm64-v8a MiuiHome Zygisk Next package."
    workingDir(layout.projectDirectory)
    commandLine(znCommand)
}

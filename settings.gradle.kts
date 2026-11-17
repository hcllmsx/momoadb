// 本地构建优先走阿里云镜像（国内直连 Google Maven / Maven Central 很慢）；
// CI 环境（GitHub Actions 等会设置 CI 变量）仍只用官方仓库，避免镜像同步滞后拖慢构建。
pluginManagement {
    val useMavenMirror = System.getenv("CI").isNullOrEmpty()
    repositories {
        if (useMavenMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useMavenMirror = System.getenv("CI").isNullOrEmpty()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMavenMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "momoadb"
include(":app")
// Android 4.4（API 19）轻量版，独立 applicationId，可与主应用共存。
include(":lite")

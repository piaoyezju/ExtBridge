import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

@Suppress("UnstableApiUsage")
android {
    namespace = "com.extbridge.helper"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.extbridge.helper"
        minSdk = 30
        targetSdk = 35
        versionCode = 25
        versionName = "3.5.3"
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.clear()
            java.directories.add("src")
            kotlin.directories.clear()
            kotlin.directories.add("src")
            res.directories.clear()
            res.directories.add("res")
            assets.directories.clear()
            assets.directories.add("assets")
        }
    }

    buildFeatures {
        compose = true
    }

    val expectedReleaseCertSha256 = "e4f67b3012a16633006c64bfc9d5a95c7bdda10826bd729777c2a3066f49117c"
    // Local overrides live in the git-ignored local.properties:
    //   keystore.file=/abs/path/to/local.keystore   keystore.type=JKS|PKCS12
    //   keystore.alias=testalias                    keystore.pass=******
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) {
            try {
                f.inputStream().use { load(it) }
            } catch (t: Throwable) {
                logger.warn("Failed to read local.properties: ${t.message}")
            }
        }
    }
    fun localProp(key: String): String? = localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    val p12File = rootProject.file("exthelper.p12")
    val hzgndzKs = rootProject.file("hzgndz.keystore")
    val localKs = localProp("keystore.file")?.let { file(it) }
    if (localKs != null && !localKs.exists()) {
        throw GradleException("keystore.file in local.properties points to non-existent file: ${localKs.absolutePath}")
    }
    val envKs = System.getenv("EXTBRIDGE_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    if (envKs != null && !envKs.exists()) {
        throw GradleException("EXTBRIDGE_KEYSTORE points to non-existent file: ${envKs.absolutePath}")
    }
    val activeKs = when {
        envKs != null -> envKs
        localKs != null -> localKs
        p12File.exists() -> p12File
        hzgndzKs.exists() -> hzgndzKs
        else -> null
    }
    val keystoreType = localProp("keystore.type")
        ?: if (activeKs != null && activeKs.name.endsWith(".p12", ignoreCase = true)) "PKCS12" else "JKS"
    val aliasCandidates = listOfNotNull(
        System.getenv("EXTBRIDGE_KEY_ALIAS")?.takeIf { it.isNotBlank() },
        localProp("keystore.alias"),
        "extbridge",
        "testalias",
    ).distinct()
    val envPass = (
        System.getenv("EXTBRIDGE_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
            ?: System.getenv("EXTBRIDGE")?.takeIf { it.isNotBlank() }
        )
    if (envKs != null && envPass == null) {
        throw GradleException("EXTBRIDGE_KEYSTORE_PASS (or EXTBRIDGE) must be explicitly set when EXTBRIDGE_KEYSTORE is provided.")
    }
    val activePass = envPass ?: localProp("keystore.pass") ?: "extbridge"
    val requireReleaseKey = (System.getenv("EXTBRIDGE_REQUIRE_RELEASE_KEY") == "1")
        || (project.findProperty("requireReleaseKey")?.toString()?.equals("true", ignoreCase = true) == true)
        || (System.getenv("GITHUB_REF")?.startsWith("refs/tags/v") == true)
    val allowUnsignedRelease = !requireReleaseKey && (
        (System.getenv("EXTBRIDGE_ALLOW_UNSIGNED_RELEASE") == "1")
            || (project.findProperty("allowUnsignedRelease")?.toString()?.equals("true", ignoreCase = true) == true)
        )
    // A developer-provided local keystore (local.properties / hzgndz.keystore) has its own
    // certificate, so the official-fingerprint gate only applies to the official key material.
    val isCustomLocalKeystore = activeKs != null && activeKs != envKs && activeKs != p12File
    val allowCustomCert = project.findProperty("allowCustomReleaseCert")?.toString()?.equals("true", ignoreCase = true) == true
        || isCustomLocalKeystore
    val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

    if ((requireReleaseKey || (isReleaseTaskRequested && !allowUnsignedRelease)) && activeKs == null) {
        throw GradleException(
            "Release build requires a keystore (EXTBRIDGE_KEYSTORE, local.properties keystore.file, exthelper.p12 or hzgndz.keystore). " +
                "Never falls back to debug signing. For keyless reproducible-build verification only, pass -PallowUnsignedRelease=true (or EXTBRIDGE_ALLOW_UNSIGNED_RELEASE=1)."
        )
    }

    var signingPass = activePass
    var signingAlias = aliasCandidates.first()
    if (activeKs != null) {
        val candidatePasswords = listOfNotNull(envPass, localProp("keystore.pass"), "extbridge").distinct()
        var loadedKs: KeyStore? = null
        var unlockedPass: String? = null
        var unlockedAlias: String? = null
        var lastErr: Throwable? = null
        for (cand in candidatePasswords) {
            try {
                val candKs = KeyStore.getInstance(keystoreType)
                activeKs.inputStream().use { stream -> candKs.load(stream, cand.toCharArray()) }
                val alias = aliasCandidates.firstOrNull { candKs.isKeyEntry(it) }
                    ?: candKs.aliases().toList().firstOrNull { candKs.isKeyEntry(it) }
                if (alias != null) {
                    loadedKs = candKs
                    unlockedPass = cand
                    unlockedAlias = alias
                    break
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        val ks = loadedKs ?: throw GradleException(
            "Failed to unlock keystore (${activeKs.name}, type=$keystoreType). " +
                "Provide the password via EXTBRIDGE_KEYSTORE_PASS or `keystore.pass` in local.properties.",
            lastErr
        )
        signingPass = unlockedPass ?: activePass
        signingAlias = unlockedAlias ?: signingAlias
        val cert = ks.getCertificate(signingAlias)
            ?: throw GradleException("Alias '$signingAlias' not found in keystore: ${activeKs.absolutePath}")
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { b -> "%02x".format(b) }
        if (!allowCustomCert && !actualSha256.equals(expectedReleaseCertSha256, ignoreCase = true)) {
            throw GradleException(
                "Release keystore certificate SHA-256 mismatch! Expected $expectedReleaseCertSha256, got $actualSha256"
            )
        }
    }

    signingConfigs {
        if (activeKs != null) {
            create("extrelease") {
                storeFile = activeKs
                storeType = keystoreType
                storePassword = signingPass
                keyAlias = signingAlias
                keyPassword = signingPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to debug keystore for release builds: if no release keystore is configured
            // (e.g. third-party reproducible-build verification), emit an unsigned APK instead of a debug-signed APK.
            signingConfig = if (activeKs != null) {
                signingConfigs.getByName("extrelease")
            } else {
                null
            }
        }
        debug {
            if (activeKs != null) {
                signingConfig = signingConfigs.getByName("extrelease")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4")
    // Shizuku privileged execution (replaces the Wireless-Debugging pairing flow)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

tasks.named("preBuild") {
    doFirst {
        val jar = file("assets/extbridge.jar")
        if (!jar.exists() || jar.length() < 4096L) {
            throw GradleException(
                "Missing or empty helper/assets/extbridge.jar! Run build_v3.ps1 (or compile the bridge daemon first) " +
                    "so the APK never ships without assets/extbridge.jar."
            )
        }
    }
}

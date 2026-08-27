plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 37

  defaultConfig {
    // Permanent production application ID (Play + direct). This is the app's
    // permanent identity on Google Play and for sideloaded updates — never change
    // it after the first public release. The internal `namespace` above stays
    // `com.example` on purpose: it only names the generated R/BuildConfig classes
    // and the ~200 existing source files, is invisible to users and Play, and
    // renaming it would be a large, risk-only refactor with no product benefit.
    applicationId = "com.bingwasokoni"
    minSdk = 24
    targetSdk = 36
    // Semantic versionName + monotonically increasing versionCode. Both the direct
    // APK and the Play AAB ship the SAME version so a user can move between the
    // GitHub and Play channels and updates supersede correctly (same signing
    // identity — see signingConfigs + docs/RELEASE_PLAYSTORE.md). Bump BOTH for
    // every release; versionCode must only ever increase.
    versionCode = 17
    versionName = "1.0.16"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Launcher label. Overridden to "Skylink Bingwa Dev" for debug builds so a debug
    // install is visually distinct from and installable alongside the release app.
    manifestPlaceholders["appLabel"] = "Skylink Bingwa"

    // Where the DEBUG build's update check reads its manifest. Non-secret. See
    // update.json at the repo root and core/update/UpdateChecker.kt. Only consulted
    // when GITHUB_UPDATER_ENABLED is true (debug only — see buildTypes below).
    // Overridable via the `updateManifestUrl` Gradle property or the
    // UPDATE_MANIFEST_URL env var.
    val updateManifestUrl = (project.findProperty("updateManifestUrl") as String?)?.takeIf { it.isNotBlank() }
      ?: System.getenv("UPDATE_MANIFEST_URL")?.takeIf { it.isNotBlank() }
      ?: "https://raw.githubusercontent.com/wazimuautomate/Skylink_Bingwa/main/update.json"
    buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")

    // Base URL of the Skylink Bingwa payment backend proxy (owns Daraja + the STK
    // callback). This is a NON-SECRET configuration value, never a credential —
    // Daraja consumer key/secret and the passkey live only on that backend
    // (CLAUDE.md §2/§10). Overridable via the `paymentsBaseUrl` Gradle property or
    // the PAYMENTS_BASE_URL env var; defaults to the production API host so a signed
    // build talks to the real backend. Real STK still also requires PAYMENTS_APP_KEY
    // (see below); without it a release build fails payments honestly rather than
    // faking a success, and a debug build falls back to a labelled local simulation.
    val paymentsBaseUrl = (project.findProperty("paymentsBaseUrl") as String?)?.takeIf { it.isNotBlank() }
      ?: System.getenv("PAYMENTS_BASE_URL")?.takeIf { it.isNotBlank() }
      ?: "https://mybingwa.blazetechscope.com/"
    buildConfigField("String", "PAYMENTS_BASE_URL", "\"$paymentsBaseUrl\"")

    // Shared app-key sent as the X-App-Key header so only our app can call the
    // payment API. NOT a Daraja credential (those stay on the server). Injected from
    // the `paymentsAppKey` Gradle property or the PAYMENTS_APP_KEY env var.
    val paymentsAppKey = (project.findProperty("paymentsAppKey") as String?)
      ?: System.getenv("PAYMENTS_APP_KEY")
      ?: ""
    buildConfigField("String", "PAYMENTS_APP_KEY", "\"$paymentsAppKey\"")

    // ---- Seller numbers bundled into the APK ---------------------------------
    // These are the CURRENT PRODUCTION values, shipped inside the app so a fresh
    // install has usable Till / Paybill / support numbers from the very first
    // launch, before any sync has succeeded.
    //
    // Why they are baked in at all: they used to default to blank, on the reasoning
    // that the owner sets them in the admin and the app syncs them. In the field
    // that meant a customer on a weak connection saw an empty Support page and
    // offline instructions that refused to show a number to pay — and the values
    // appeared, or appeared and vanished, depending purely on whether a sync had
    // landed. These are not secrets (they are printed on the seller's own posters),
    // so shipping them is the honest fix.
    //
    // They remain the FLOOR, never the truth: a successful sync overwrites them, and
    // the admin stays the place to change a number. Override per build with the
    // Gradle properties below (or the matching env vars) when a number changes
    // before the next release.
    fun sellerValue(propertyName: String, envName: String, fallback: String): String =
      (project.findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: fallback

    buildConfigField(
      "String", "SEED_TILL_NUMBER",
      "\"${sellerValue("sellerTillNumber", "SELLER_TILL_NUMBER", "4063396")}\""
    )
    buildConfigField(
      "String", "SEED_PAYBILL_NUMBER",
      "\"${sellerValue("sellerPaybillNumber", "SELLER_PAYBILL_NUMBER", "4008239")}\""
    )
    buildConfigField(
      "String", "SEED_SUPPORT_NUMBER",
      "\"${sellerValue("sellerSupportNumber", "SELLER_SUPPORT_NUMBER", "0110092715")}\""
    )
    buildConfigField(
      "String", "SEED_SUPPORT_WHATSAPP",
      "\"${sellerValue("sellerSupportWhatsapp", "SELLER_SUPPORT_WHATSAPP", "0717444266")}\""
    )
  }

  // Two distribution channels from one codebase and one signing identity.
  flavorDimensions += "distribution"
  productFlavors {
    // GitHub / direct-download build.
    create("direct") {
      dimension = "distribution"
      // The GitHub updater is a DEBUG-ONLY development convenience, on both
      // flavours. This flavour default is what a `directDebug` build gets; the
      // `release` build type below overrides it to false, and a build type always
      // wins over a flavour — so no shipped APK or AAB, direct or Play, ever
      // fetches update.json or offers to install anything. Google Play is the only
      // update channel for a production build, which is both what Play policy
      // requires and what keeps the app out of a rejection.
      buildConfigField("boolean", "GITHUB_UPDATER_ENABLED", "true")
    }
    // Google Play build. Identical applicationId and signing identity as `direct`,
    // so the two channels are update-compatible. The only thing it drops is
    // REQUEST_INSTALL_PACKAGES, which Play builds never need.
    create("play") {
      dimension = "distribution"
      // Play distribution updates itself natively. Shipping a second, in-app
      // update channel there is redundant and violates Play policy, so the
      // GitHub updater is compiled out of this flavour. The implementation is
      // retained (not deleted) behind this flag so it can be re-enabled if
      // distribution ever changes.
      buildConfigField("boolean", "GITHUB_UPDATER_ENABLED", "false")
    }
  }

  signingConfigs {
    create("release") {
      // Release signing is supplied only in the protected CI release job via env vars.
      // No keystore is committed. Missing values are tolerated until a release variant is signed.
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        initWith(getByName("debug"))
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
      // The GitHub in-app update check is a DEVELOPMENT convenience only. Shipped
      // builds never look at update.json: Google Play distributes and updates the
      // production app natively, and a Play build that offered to download and
      // install an APK from GitHub would both fail (the play flavour removes
      // REQUEST_INSTALL_PACKAGES) and breach Play's distribution policy. With this
      // false, MainActivity skips the start-up check and Settings hides the
      // "Check for updates" control entirely.
      buildConfigField("boolean", "GITHUB_UPDATER_ENABLED", "false")
    }
    // Debug is installable alongside release: a distinct application-id suffix and
    // the "Skylink Bingwa Dev" launcher label. Uses AGP's auto-generated debug
    // keystore — never a committed one, and never the permanent release identity.
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      manifestPlaceholders["appLabel"] = "Skylink Bingwa Dev"
      // A buildType field overrides the flavour field, so debug builds always keep
      // the GitHub updater — including the `play` debug variant, which testers
      // install by sideloading and therefore still need an in-app upgrade path.
      buildConfigField("boolean", "GITHUB_UPDATER_ENABLED", "true")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
    disable += setOf("MissingTranslation", "ExtraTranslation")
  }
}

// Writes a google-services.json when the repo/CI does not provide one, so a build never
// fails merely because the (git-ignored) real file is absent.
//
// CONFIGURATION-CACHE NOTE: every value the execution-time action touches is captured as a
// LOCAL of this configure lambda. In a .gradle.kts script a top-level `val` compiles to a
// property of the script class, so reading one from inside doLast captures the script
// object itself — which the configuration cache refuses to serialise
// ("cannot serialize Gradle script object references") and which failed the build. Locals
// are captured by value, so the action closes over nothing but a File and two Strings.
val ensureGoogleServicesJson = tasks.register("ensureGoogleServicesJson") {
  val gsFile = layout.projectDirectory.file("google-services.json").asFile
  val envJson = providers.environmentVariable("GOOGLE_SERVICES_JSON").orNull
  val stubJson = """
    {
      "project_info": {
        "project_number": "1234567890",
        "project_id": "skylink-bingwa",
        "storage_bucket": "skylink-bingwa.firebasestorage.app"
      },
      "client": [
        {
          "client_info": {
            "mobilesdk_app_id": "1:1234567890:android:abcdef123456",
            "android_client_info": {
              "package_name": "com.bingwasokoni"
            }
          },
          "oauth_client": [],
          "api_key": [
            {
              "current_key": "fake_api_key_for_ci_build"
            }
          ],
          "services": {}
        },
        {
          "client_info": {
            "mobilesdk_app_id": "1:1234567890:android:abcdef123457",
            "android_client_info": {
              "package_name": "com.bingwasokoni.debug"
            }
          },
          "oauth_client": [],
          "api_key": [
            {
              "current_key": "fake_api_key_for_ci_build"
            }
          ],
          "services": {}
        }
      ],
      "configuration_version": "1"
    }
  """.trimIndent()

  doLast {
    if (!gsFile.exists()) {
      gsFile.writeText(if (!envJson.isNullOrBlank()) envJson else stubJson)
    }
  }
}


tasks.matching { it.name.startsWith("process") && it.name.contains("GoogleServices") }.configureEach {
  dependsOn(ensureGoogleServicesJson)
}

// The imported Google AI Studio project shipped Gemini/Firebase and KSP codegen
// scaffolding that Skylink Bingwa does not use (Plan.md excludes Gemini and Firebase-AI
// from the APK; no Room entities or Moshi codegen exist yet). Those plugins have
// been removed here: they added APK weight and, on CI, the KSP2 processor crashed
// during annotation processing. Room and kotlinx.serialization are reintroduced
// with real code in later phases (see docs/CLAUDE_KICKOFF_AND_BUILD_PHASES.md).
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  // Fonts (Outfit + Poppins) are bundled under res/font; the downloadable
  // Google Fonts provider is intentionally not used (see ui/theme/Type.kt).
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  // Billboard media: Coil renders synced billboard images and animated GIFs from
  // its own on-disk cache, so a slide that was fetched once keeps rendering
  // OFFLINE. coil-gif adds the animated-GIF decoders (ImageDecoder on API 28+,
  // Movie below it). No network is required for a cache hit.
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  // Preferences DataStore backs the real on-device persistence (LocalStore): the
  // installation-local profile, favourites, Activity, notifications and any in-flight
  // order survive process death. No KSP/codegen — the snapshot is Moshi JSON.
  implementation(libs.androidx.datastore.preferences)
  // Room runtime is declared ahead of a future migration to a relational local
  // source; no entities/DAOs exist yet, so the KSP compiler is not applied.
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // WorkManager runs the periodic background catalogue/config sync
  // (CatalogueSyncWorker) under a CONNECTED constraint with exponential backoff.
  // Scheduled once from SkylinkBingwaApplication; never blocks startup.
  implementation(libs.androidx.work.runtime.ktx)
  // Retrofit/OkHttp/Moshi are declared ahead of the Phase 7 network layer.
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  // Google Play in-app review, PLAY FLAVOUR ONLY.
  "playImplementation"("com.google.android.play:review-ktx:2.0.2")
  // Google Play in-app updates (immediate non-dismissible modal), PLAY FLAVOUR ONLY.
  "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")
  // Firebase Cloud Messaging (remote admin push notifications)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

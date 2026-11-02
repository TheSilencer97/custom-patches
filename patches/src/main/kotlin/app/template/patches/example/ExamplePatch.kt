package app.morphe.patches.example

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.io.File
import java.util.logging.Logger

private val logger = Logger.getLogger("CollapseAdSpace")

private val entitlementKeys = listOf(
    "entitlement", "entitlements", "offering", "\$rc_annual", "annual", "yearly", "ANNUAL",
    "Subscribed to Ad-Free (Yearly)", "com.liquipedia.app", "com.teamliquid.liquipedia",
    "ad_free", "ad-free", "adfree", "AdFree", "adFree", "Ad-Free", "AD_FREE",
    "Ad Free", "ad free", "Ad free",
    "Remove Ads", "Remove ads", "remove_ads", "remove-ads", "removeAds", "removeads",
    "No Ads", "no_ads", "no-ads", "noAds", "noads",
    "ad_free_experience", "ad-free-experience", "Ad-Free Experience",
    "ad_free_access", "ad_free_yearly", "ad_free_monthly", "ad_free_subscription", "ad_free_entitlement",
    "liquipedia_ad_free", "liquipedia_pro", "liquipedia_premium", "liquipedia_supporter", "liquipedia_turbo",
    "pro", "Pro", "premium", "Premium", "plus", "Plus", "supporter", "turbo", "Turbo", "default"
)

private val adTokenRegex = Regex(
    "(?i)(^|[._/\\-])(ad|ads|adview|admob|applovin|banner|dfp|gam|mopub|mrec|native_ad|ad_unit|ad_container|ad_wrapper|ad_slot|ad_holder|ad_frame|ad_space|ad_banner|banner_ad|ad_view|ad_layout|ad_placeholder|ad_card|ad_item|ad_box|ad_strip|ad_bar|sponsored_ad)([._/\\-]|$)" +
        "|(?i)(^|[._/\\-])(ad|ads|banner|dfp|gam|mopub|mrec)(View|Container|Wrapper|Layout|Frame|Holder|Slot|Unit|Banner|Space|Card|Item|Box|Size|Height|Width|Margin|Padding|Placeholder|Header|Footer|Strip|Bar|Loader|Controller|Manager|Helper|Adapter|Module|Section|Zone|Block)s?([._/\\-]|$)" +
        "|((^|[a-z0-9_/$])(Ad|Ads|Banner|Dfp|Gam|Mopub|Mrec|NativeAd)(View|Container|Wrapper|Layout|Frame|Holder|Slot|Unit|Banner|Space|Card|Item|Box|Size|Height|Width|Placeholder|Strip|Bar|Loader|Request|Manager|InstanceManager|Plugin|Widget|Factory|Listener|Callback)s?($|[A-Z0-9_;]))" +
        "|((^|[a-z0-9_/$])(Flutter|GoogleMobile|Max|Banner|Native|Interstitial|Rewarded|AppOpen|Fluid|Publisher|AdManager)(Ad|Ads|BannerAd|NativeAd|AdView|AdSize|AdLoader|AdRequest)s?($|[A-Z0-9_;]))"
)

private val adViewClassRegex = Regex(
    "(?i).*(AdView|BannerView|BannerAdView|AdContainer|AdWrapper|AdLayout|AdSlot|AdFrame|AdHolder|AdPlaceholder|BannerContainer|AdBanner|DfpAd|GamAd|MaxAdView|PublisherAdView|AdManagerAdView|NativeAdView);$"
)

private val adSizeClassRegex = Regex(
    "(?i).*(/AdSize|/FlutterAdSize|/FlutterAdSize\\\$[A-Za-z0-9_]+|/BannerAdSize|/MaxAdFormat);$"
)

private val adSdkPackageRegex = Regex(
    "^L(io/flutter/plugins/googlemobileads|com/google/android/gms/ads|com/yandex/mobile/ads|com/facebook/ads|com/applovin/mediation|com/applovin/adview|com/mopub/mobileads)/.*"
)

private val adLoadMethodRegex = Regex(
    "^(load|loadAd|loadAds|loadBanner|loadBannerAd|loadNativeAd|loadInterstitialAd|loadRewardedAd|loadRewardedInterstitialAd|loadAppOpenAd|loadAdManagerBannerAd|loadAdManagerInterstitialAd|loadFluidAd|onAdLoaded|show|showAd|showInterstitial|requestBannerAd|requestInterstitialAd|requestNativeAd)$"
)

private fun isAdRelated(text: String): Boolean {
    if (text.isEmpty()) return false
    val cleaned = text.substringAfterLast('/')
    return adTokenRegex.containsMatchIn(cleaned) ||
        text.contains("com.google.android.gms.ads", true) ||
        text.contains("io.flutter.plugins.googlemobileads", true)
}

private fun collapseElement(el: Element) {
    el.setAttribute("android:visibility", "gone")
    el.setAttribute("android:layout_height", "0.0dip")
    el.setAttribute("android:layout_width", "0.0dip")
    el.setAttribute("android:minHeight", "0.0dip")
    el.setAttribute("android:minWidth", "0.0dip")
}

private fun extractStringsFromBinary(file: File): List<String> {
    val result = mutableListOf<String>()
    val bytes = file.readBytes()
    val sb = StringBuilder()
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        if (c in 32..126) {
            sb.append(c.toChar())
        } else {
            if (sb.length in 3..140) {
                result.add(sb.toString())
            }
            sb.setLength(0)
        }
    }
    return result
}

private val collapseAdResourcesPatch = resourcePatch {
    execute {
        val rootDir = get(".")
        rootDir.walkTopDown().filter { it.isFile && it.extension.equals("xml", true) }.forEach { file ->
            val relPath = file.relativeTo(rootDir).invariantSeparatorsPath
            val parentName = file.parentFile?.name?.lowercase() ?: ""
            if (parentName.startsWith("values") && file.name.startsWith("dimens")) {
                runCatching {
                    document(relPath).use { doc ->
                        val dimens = doc.getElementsByTagName("dimen")
                        for (i in 0 until dimens.length) {
                            val item = dimens.item(i) as? Element ?: continue
                            if (isAdRelated(item.getAttribute("name"))) item.textContent = "0.0dip"
                        }
                    }
                }
            } else if (parentName.startsWith("layout")) {
                runCatching {
                    document(relPath).use { doc ->
                        val rootEl = doc.documentElement ?: return@use
                        if (isAdRelated(file.nameWithoutExtension)) {
                            collapseElement(rootEl)
                        } else {
                            val all = doc.getElementsByTagName("*")
                            for (i in 0 until all.length) {
                                val el = all.item(i) as? Element ?: continue
                                if (isAdRelated(el.tagName ?: "") || isAdRelated(el.getAttribute("android:id"))) {
                                    collapseElement(el)
                                }
                            }
                        }
                    }
                }
            }
        }

        val candidatePaths = listOf(
            "lib/arm64-v8a/libapp.so",
            "lib/armeabi-v7a/libapp.so",
            "lib/x86_64/libapp.so"
        )
        for (rel in candidatePaths) {
            runCatching {
                val libFile = get(rel)
                if (libFile.exists() && libFile.isFile) {
                    val strings = extractStringsFromBinary(libFile)

                    // 1. Print neighbors around key anchors in libapp.so
                    val anchors = setOf(
                        "goog_yAwjsWpnDhBAvIuJeUqeJDiyTie",
                        "_hasAdFreeEntitlement@1256349677",
                        "_mapPackageType@1256349677",
                        "entitlement",
                        "showRemoveAdsButton",
                        "_remove_ads_button_beneath_large_ads_type"
                    )
                    for (i in strings.indices) {
                        val s = strings[i]
                        if (s in anchors) {
                            val start = (i - 8).coerceAtLeast(0)
                            val end = (i + 8).coerceAtMost(strings.size - 1)
                            logger.info("[X-Ray Anchor] '$s' -> ${strings.subList(start, end + 1)}")
                        }
                    }

                    // 2. Find all private @<id> symbols related to AdStore / Ads / Purchases / Restore
                    val privRegex = Regex("(?i).*(adFree|removeAds|entitle|adStore|bannerAd|restore|offering|purchase).*@[0-9]+.*")
                    val privHits = strings.filter { privRegex.matches(it) }.distinct()
                    logger.info("[X-Ray PrivSymbols] (${privHits.size}): $privHits")
                }
            }
        }
    }
}

@Suppress("unused")
val examplePatch = bytecodePatch(
    name = "Collapse Ad Space",
    description = "Collapses empty ad banners, placeholders, Flutter ads, and RevenueCat ad-free state.",
    default = false,
) {
    dependsOn(collapseAdResourcesPatch)

    execute {
        val targetClasses = mutableSetOf<String>()
        val discoveredAdSizeClasses = mutableSetOf("Lcom/google/android/gms/ads/AdSize;")
        val discoveredAdMobPackages = mutableSetOf<String>()
        val discoveredFlutterAdsPackages = mutableSetOf<String>()
        var flutterAdBaseType: String? = null
        var flutterAdIdFieldName: String? = null
        var flutterLoadAdErrorType: String? = null
        var flutterResponseInfoType: String? = null
        var adInstanceManagerType: String? = null
        var onAdFailedMethodName: String? = null

        classDefForEach { classDef ->
            val type = classDef.type
            if (type == "Lcom/google/ads/mediation/AbstractAdViewAdapter;") {
                for (m in classDef.methods) {
                    if (m.name == "requestBannerAd" && m.parameterTypes.size >= 4) {
                        val adSizeType = m.parameterTypes[3].toString()
                        discoveredAdSizeClasses.add(adSizeType)
                        discoveredAdMobPackages.add(adSizeType.substringBeforeLast('/') + "/")
                    }
                }
            }
            if (classDef.methods.any { it.returnType == "Lcom/google/android/gms/ads/nativead/NativeAdView;" } &&
                !type.startsWith("Lcom/google/")
            ) {
                for (iface in classDef.interfaces) {
                    val pkg = iface.substringBeforeLast('/') + "/"
                    if (!pkg.startsWith("Ljava/") && !pkg.startsWith("Landroid/")) {
                        discoveredFlutterAdsPackages.add(pkg)
                    }
                }
            }
        }

        classDefForEach { classDef ->
            val type = classDef.type
            val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
            val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

            if (isInUnmaskedFlutterAds) {
                val fourArgInit = classDef.methods.firstOrNull {
                    it.name == "<init>" && it.parameterTypes.size == 4 &&
                        it.parameterTypes[0] == "I" &&
                        it.parameterTypes[1] == "Ljava/lang/String;" &&
                        it.parameterTypes[2] == "Ljava/lang/String;"
                }
                if (fourArgInit != null) {
                    flutterLoadAdErrorType = type
                    flutterResponseInfoType = fourArgInit.parameterTypes[3].toString()
                }
                if (classDef.superclass == "Ljava/lang/Object;" &&
                    classDef.methods.any { it.name == "<init>" && it.parameterTypes.size == 1 && it.parameterTypes[0] == "I" } &&
                    classDef.methods.any { it.returnType.startsWith("Lio/flutter/plugin/platform/") }
                ) {
                    flutterAdBaseType = type
                    flutterAdIdFieldName = classDef.fields.firstOrNull { it.type == "I" }?.name
                }
            }

            if (isInUnmaskedAdMob || isInUnmaskedFlutterAds ||
                discoveredAdSizeClasses.contains(type) ||
                type == "Lcom/revenuecat/purchases/hybridcommon/mappers/EntitlementInfosMapperKt;" ||
                type == "Lcom/revenuecat/purchases/hybridcommon/mappers/OfferingsMapperKt;" ||
                type == "Lcom/revenuecat/purchases/hybridcommon/mappers/StoreProductMapperKt;" ||
                type == "Lcom/revenuecat/purchases/CustomerInfo;" ||
                adSizeClassRegex.matches(type) ||
                adViewClassRegex.matches(type) ||
                adSdkPackageRegex.matches(type)
            ) {
                targetClasses.add(type)
            }
        }

        if (flutterAdBaseType != null && flutterLoadAdErrorType != null) {
            classDefForEach { classDef ->
                if (discoveredFlutterAdsPackages.any { classDef.type.startsWith(it) }) {
                    val failMethod = classDef.methods.firstOrNull {
                        it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == "I" &&
                            it.parameterTypes[1].toString() == flutterLoadAdErrorType &&
                            it.returnType == "V"
                    }
                    if (failMethod != null) {
                        adInstanceManagerType = classDef.type
                        onAdFailedMethodName = failMethod.name
                    }
                }
            }
        }

        for (type in targetClasses) {
            runCatching {
                val mutableClass = mutableClassDefBy(type)
                val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
                val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

                // 0. Safely capture live Offering ID, Package ID, Product ID, and SKU using move-object/from16
                if (type == "Lcom/revenuecat/purchases/hybridcommon/mappers/OfferingsMapperKt;") {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name == "map" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "Lcom/revenuecat/purchases/Offering;" &&
                            regCount >= 4
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        move-object/from16 v0, p0
                                        invoke-virtual {v0}, Lcom/revenuecat/purchases/Offering;->getIdentifier()Ljava/lang/String;
                                        move-result-object v0
                                        const-string v1, "rc.off"
                                        invoke-static {v1, v0}, Ljava/lang/System;->setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                                    """
                                )
                                logger.info("[X-Ray RevenueCat] Hooked OfferingsMapperKt.map(Offering) safely")
                            }
                        } else if (method.name == "map" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "Lcom/revenuecat/purchases/Package;" &&
                            regCount >= 4
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        move-object/from16 v0, p0
                                        invoke-virtual {v0}, Lcom/revenuecat/purchases/Package;->getIdentifier()Ljava/lang/String;
                                        move-result-object v0
                                        const-string v1, "rc.pkg"
                                        invoke-static {v1, v0}, Ljava/lang/System;->setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                                    """
                                )
                                logger.info("[X-Ray RevenueCat] Hooked OfferingsMapperKt.map(Package) safely")
                            }
                        }
                    }
                } else if (type == "Lcom/revenuecat/purchases/hybridcommon/mappers/StoreProductMapperKt;") {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name == "map" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "Lcom/revenuecat/purchases/models/StoreProduct;" &&
                            regCount >= 4
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        move-object/from16 v0, p0
                                        invoke-interface {v0}, Lcom/revenuecat/purchases/models/StoreProduct;->getId()Ljava/lang/String;
                                        move-result-object v0
                                        const-string v1, "rc.prod"
                                        invoke-static {v1, v0}, Ljava/lang/System;->setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                                        move-object/from16 v0, p0
                                        invoke-interface {v0}, Lcom/revenuecat/purchases/models/StoreProduct;->getSku()Ljava/lang/String;
                                        move-result-object v0
                                        const-string v1, "rc.sku"
                                        invoke-static {v1, v0}, Ljava/lang/System;->setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                                    """
                                )
                                logger.info("[X-Ray RevenueCat] Hooked StoreProductMapperKt.map safely")
                            }
                        }
                    }
                }

                // 1. Build 100% crash-proof EntitlementInfo map with non-null future Long expirationDate + live props
                if (type == "Lcom/revenuecat/purchases/hybridcommon/mappers/EntitlementInfosMapperKt;") {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name == "map" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "Lcom/revenuecat/purchases/EntitlementInfos;" &&
                            method.returnType == "Ljava/util/Map;" &&
                            regCount >= 6
                        ) {
                            val sb = StringBuilder()
                            sb.appendLine("new-instance v0, Ljava/util/HashMap;")
                            sb.appendLine("invoke-direct {v0}, Ljava/util/HashMap;-><init>()V")
                            sb.appendLine("sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;")
                            sb.appendLine("const-string v1, \"isActive\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"willRenew\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("sget-object v2, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;")
                            sb.appendLine("const-string v1, \"isSandbox\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"periodType\"")
                            sb.appendLine("const-string v2, \"NORMAL\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"latestPurchaseDate\"")
                            sb.appendLine("const-string v2, \"2026-01-01T00:00:00Z\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"originalPurchaseDate\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")

                            // Genuine java.lang.Long purchase timestamps
                            sb.appendLine("new-instance v2, Ljava/util/Date;")
                            sb.appendLine("invoke-direct {v2}, Ljava/util/Date;-><init>()V")
                            sb.appendLine("invoke-static {v2}, Lcom/revenuecat/purchases/hybridcommon/mappers/MappersHelpersKt;->toMillis(Ljava/util/Date;)J")
                            sb.appendLine("move-result-wide v2")
                            sb.appendLine("invoke-static {v2, v3}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;")
                            sb.appendLine("move-result-object v2")
                            sb.appendLine("const-string v1, \"latestPurchaseDateMillis\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"originalPurchaseDateMillis\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")

                            // Future expirationDate & Long expirationDateMillis via Iso8601Utils.parse
                            sb.appendLine("const-string v1, \"expirationDate\"")
                            sb.appendLine("const-string v2, \"2030-12-22T04:49:08Z\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("invoke-static {v2}, Lcom/revenuecat/purchases/utils/Iso8601Utils;->parse(Ljava/lang/String;)Ljava/util/Date;")
                            sb.appendLine("move-result-object v2")
                            sb.appendLine("invoke-static {v2}, Lcom/revenuecat/purchases/hybridcommon/mappers/MappersHelpersKt;->toMillis(Ljava/util/Date;)J")
                            sb.appendLine("move-result-wide v2")
                            sb.appendLine("invoke-static {v2, v3}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;")
                            sb.appendLine("move-result-object v2")
                            sb.appendLine("const-string v1, \"expirationDateMillis\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")

                            // Reset v2 and v3 cleanly
                            sb.appendLine("const/4 v2, 0x0")
                            sb.appendLine("const/4 v3, 0x0")
                            sb.appendLine("const-string v1, \"unsubscribeDetectedAt\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"unsubscribeDetectedAtMillis\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"billingIssueDetectedAt\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"billingIssueDetectedAtMillis\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"productPlanIdentifier\"")
                            sb.appendLine("const-string v2, \"yearly\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"store\"")
                            sb.appendLine("const-string v2, \"PLAY_STORE\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")

                            // Use live captured productIdentifier ("rc.prod") if available
                            sb.appendLine("const-string v1, \"rc.prod\"")
                            sb.appendLine("const-string v2, \"ad_free\"")
                            sb.appendLine("invoke-static {v1, v2}, Ljava/lang/System;->getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                            sb.appendLine("move-result-object v2")
                            sb.appendLine("const-string v1, \"productIdentifier\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")

                            sb.appendLine("const-string v1, \"ownershipType\"")
                            sb.appendLine("const-string v2, \"PURCHASED\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v1, \"verification\"")
                            sb.appendLine("const-string v2, \"VERIFIED\"")
                            sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")

                            sb.appendLine("new-instance v1, Ljava/util/HashMap;")
                            sb.appendLine("invoke-direct {v1}, Ljava/util/HashMap;-><init>()V")
                            sb.appendLine("const-string v4, \"identifier\"")
                            for (key in entitlementKeys) {
                                sb.appendLine("new-instance v2, Ljava/util/HashMap;")
                                sb.appendLine("invoke-direct {v2, v0}, Ljava/util/HashMap;-><init>(Ljava/util/Map;)V")
                                sb.appendLine("const-string v3, \"$key\"")
                                sb.appendLine("invoke-virtual {v2, v4, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                                sb.appendLine("invoke-virtual {v1, v3, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            }
                            for (prop in listOf("rc.off", "rc.pkg", "rc.prod", "rc.sku")) {
                                sb.appendLine("const-string v3, \"$prop\"")
                                sb.appendLine("const-string v2, \"default\"")
                                sb.appendLine("invoke-static {v3, v2}, Ljava/lang/System;->getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                                sb.appendLine("move-result-object v3")
                                sb.appendLine("new-instance v2, Ljava/util/HashMap;")
                                sb.appendLine("invoke-direct {v2, v0}, Ljava/util/HashMap;-><init>(Ljava/util/Map;)V")
                                sb.appendLine("invoke-virtual {v2, v4, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                                sb.appendLine("invoke-virtual {v1, v3, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            }

                            sb.appendLine("new-instance v0, Ljava/util/HashMap;")
                            sb.appendLine("invoke-direct {v0}, Ljava/util/HashMap;-><init>()V")
                            sb.appendLine("const-string v2, \"all\"")
                            sb.appendLine("invoke-virtual {v0, v2, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v2, \"active\"")
                            sb.appendLine("invoke-virtual {v0, v2, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v2, \"verification\"")
                            sb.appendLine("const-string v3, \"VERIFIED\"")
                            sb.appendLine("invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("return-object v0")

                            runCatching {
                                method.addInstructions(0, sb.toString())
                                logger.info("[X-Ray RevenueCat] Hooked EntitlementInfosMapperKt.map safely with future Long expirationDate + live props")
                            }
                        }
                    }
                } else if (type == "Lcom/revenuecat/purchases/CustomerInfo;") {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if ((method.name == "getActiveSubscriptions" || method.name == "getAllPurchasedProductIds" || method.name == "getAllPurchasedSkus") &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == "Ljava/util/Set;" &&
                            regCount >= 2
                        ) {
                            val sb = StringBuilder()
                            sb.appendLine("new-instance v0, Ljava/util/HashSet;")
                            sb.appendLine("invoke-direct {v0}, Ljava/util/HashSet;-><init>()V")
                            for (key in entitlementKeys) {
                                sb.appendLine("const-string p0, \"$key\"")
                                sb.appendLine("invoke-virtual {v0, p0}, Ljava/util/HashSet;->add(Ljava/lang/Object;)Z")
                            }
                            for (prop in listOf("rc.off", "rc.pkg", "rc.prod", "rc.sku")) {
                                sb.appendLine("const-string p0, \"$prop\"")
                                sb.appendLine("invoke-static {p0, p0}, Ljava/lang/System;->getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                                sb.appendLine("move-result-object p0")
                                sb.appendLine("invoke-virtual {v0, p0}, Ljava/util/HashSet;->add(Ljava/lang/Object;)Z")
                            }
                            sb.appendLine("return-object v0")
                            runCatching {
                                method.addInstructions(0, sb.toString())
                                logger.info("[X-Ray RevenueCat] Hooked CustomerInfo->${method.name} with all keys + live props")
                            }
                        } else if (method.name == "getLatestExpirationDate" &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == "Ljava/util/Date;" &&
                            regCount >= 2
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const-string v0, "2030-12-22T04:49:08Z"
                                        invoke-static {v0}, Lcom/revenuecat/purchases/utils/Iso8601Utils;->parse(Ljava/lang/String;)Ljava/util/Date;
                                        move-result-object v0
                                        return-object v0
                                    """
                                )
                                logger.info("[X-Ray RevenueCat] Hooked CustomerInfo->getLatestExpirationDate")
                            }
                        }
                    }
                }

                // 2. Hook FlutterAd subclasses (Ls9/r;, Ls9/k;, Ls9/x;, etc.) at index 0 of load()
                if (isInUnmaskedFlutterAds && flutterAdBaseType != null && flutterAdIdFieldName != null &&
                    adInstanceManagerType != null && onAdFailedMethodName != null &&
                    flutterLoadAdErrorType != null && flutterResponseInfoType != null
                ) {
                    val superCls = mutableClass.superclass ?: ""
                    val mgrField = mutableClass.fields.firstOrNull { it.type == adInstanceManagerType }?.name
                    if ((superCls == flutterAdBaseType || superCls.startsWith(flutterAdBaseType.removeSuffix(";") + "$")) &&
                        mgrField != null
                    ) {
                        for (method in mutableClass.methods) {
                            val regCount = method.implementation?.registerCount ?: 0
                            if ((method.name == "d" || method.name == "e" || method.name == "h") &&
                                method.parameterTypes.isEmpty() &&
                                method.returnType == "V" &&
                                regCount in 5..15
                            ) {
                                runCatching {
                                    method.addInstructions(
                                        0,
                                        """
                                            new-instance v0, $flutterLoadAdErrorType
                                            const/4 v1, 0x3
                                            const-string v2, "No fill"
                                            const/4 v3, 0x0
                                            invoke-direct {v0, v1, v2, v2, v3}, $flutterLoadAdErrorType-><init>(ILjava/lang/String;Ljava/lang/String;$flutterResponseInfoType)V
                                            iget-object v1, p0, $type->$mgrField:$adInstanceManagerType
                                            iget v2, p0, $flutterAdBaseType->$flutterAdIdFieldName:I
                                            invoke-virtual {v1, v2, v0}, $adInstanceManagerType->$onAdFailedMethodName(I$flutterLoadAdErrorType)V
                                            return-void
                                        """
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Zero out AdSize & helpers
                if (discoveredAdSizeClasses.contains(type) || adSizeClassRegex.matches(type)) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.returnType == "I" && method.name != "hashCode" &&
                            (method.parameterTypes.isEmpty() ||
                                (method.parameterTypes.size == 1 && method.parameterTypes[0] == "Landroid/content/Context;")) &&
                            regCount >= 1
                        ) {
                            runCatching { method.addInstructions(0, "const/4 v0, 0x0\nreturn v0") }
                        }
                    }
                }

                if (isInUnmaskedAdMob) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.parameterTypes.size == 1 &&
                            discoveredAdSizeClasses.contains(method.parameterTypes[0].toString()) &&
                            method.returnType == "I" && regCount >= 1
                        ) {
                            runCatching { method.addInstructions(0, "const/4 v0, 0x0\nreturn v0") }
                        }
                    }
                }

                // 4. Block standard named ad load methods & collapse Views
                if (adSdkPackageRegex.matches(type) || adViewClassRegex.matches(type) ||
                    type == "Lcom/google/ads/mediation/AbstractAdViewAdapter;"
                ) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (adLoadMethodRegex.matches(method.name) && method.returnType == "V" && regCount >= 1) {
                            runCatching { method.addInstructions(0, "return-void") }
                        } else if (method.name == "onMeasure" && method.parameterTypes.size == 2 &&
                            method.parameterTypes[0] == "I" && method.parameterTypes[1] == "I" &&
                            method.returnType == "V" && regCount in 3..15
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    "const/4 v0, 0x0\ninvoke-virtual {p0, v0, v0}, Landroid/view/View;->setMeasuredDimension(II)V\nreturn-void"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

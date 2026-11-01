package app.morphe.patches.example

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.io.File
import java.util.logging.Logger
import java.util.zip.ZipFile

private val logger = Logger.getLogger("CollapseAdSpace")

private val entitlementKeys = mutableSetOf(
    "ad_free", "ad-free", "adfree", "AdFree", "adFree", "Ad-Free",
    "ad_free_access", "ad_free_yearly", "ad_free_monthly", "ad_free_subscription",
    "remove_ads", "removeads", "no_ads", "noads", "pro", "premium",
    "turbo", "plus", "supporter", "vip", "default", "entitlement",
    "liquipedia_ad_free", "liquipedia_pro", "subscription", "yearly", "annual"
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

private val collapseAdResourcesPatch = resourcePatch {
    execute {
        runCatching {
            val origDir = File("/data/user/0/app.morphe.manager/app_original-apks")
            val apkFile = origDir.listFiles()?.filter { it.extension == "apk" }?.maxByOrNull { it.lastModified() }
            if (apkFile != null && apkFile.exists()) {
                ZipFile(apkFile).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/libapp.so")
                    if (entry != null) {
                        val text = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                        val matches = Regex("[A-Za-z0-9_\\-.:/]{3,50}").findAll(text).map { it.value }.toList()
                        for (idx in matches.indices) {
                            val s = matches[idx]
                            if (s.contains("hasAdFreeEntitlement", true) ||
                                s.contains("getAdFreeEntitlementStatus", true) ||
                                s.contains("ad_free_bottom_sheet", true)
                            ) {
                                val win = matches.subList((idx - 15).coerceAtLeast(0), (idx + 16).coerceAtMost(matches.size))
                                logger.info("[X-Ray DartWindow] Near '$s': $win")
                                win.filter {
                                    it.length in 3..30 && !it.contains("/") && !it.contains(":") && !it.contains(".") &&
                                        it.first().isLetter()
                                }.forEach { entitlementKeys.add(it) }
                            }
                        }
                    }
                }
            }
        }

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
        var flutterLoadAdErrorType: String? = null
        var flutterResponseInfoType: String? = null

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
                // Identify Ls9/f$c; (FlutterLoadAdError) by its 4-arg constructor (I, String, String, Ls9/f$e;)
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
                // Identify Ls9/f; (FlutterAd base class) by c()Lio/flutter/plugin/platform/k; and <init>(I)V
                if (classDef.superclass == "Ljava/lang/Object;" &&
                    classDef.methods.any { it.name == "<init>" && it.parameterTypes.size == 1 && it.parameterTypes[0] == "I" } &&
                    classDef.methods.any { it.returnType.startsWith("Lio/flutter/plugin/platform/") }
                ) {
                    flutterAdBaseType = type
                }
            }

            if (isInUnmaskedAdMob || isInUnmaskedFlutterAds ||
                discoveredAdSizeClasses.contains(type) ||
                type == "Lcom/revenuecat/purchases/hybridcommon/mappers/EntitlementInfosMapperKt;" ||
                type == "Lcom/revenuecat/purchases/CustomerInfo;" ||
                adSizeClassRegex.matches(type) ||
                adViewClassRegex.matches(type) ||
                adSdkPackageRegex.matches(type)
            ) {
                targetClasses.add(type)
            }
        }

        logger.info("[X-Ray FlutterIds] base=$flutterAdBaseType, err=$flutterLoadAdErrorType, resp=$flutterResponseInfoType")

        for (type in targetClasses) {
            runCatching {
                val mutableClass = mutableClassDefBy(type)
                val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
                val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

                // 1. Bridge RevenueCat directly in EntitlementInfosMapperKt.map(EntitlementInfos)
                if (type == "Lcom/revenuecat/purchases/hybridcommon/mappers/EntitlementInfosMapperKt;") {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        logger.info("[X-Ray RCMapper] ${method.name} regCount=$regCount")
                        if (method.name == "map" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "Lcom/revenuecat/purchases/EntitlementInfos;" &&
                            method.returnType == "Ljava/util/Map;" &&
                            regCount >= 4
                        ) {
                            val sb = StringBuilder()
                            sb.appendLine("new-instance v0, Ljava/util/HashMap;")
                            sb.appendLine("invoke-direct {v0}, Ljava/util/HashMap;-><init>()V")
                            var idx = 0
                            for (key in entitlementKeys) {
                                val safeKey = key.replace("\"", "")
                                sb.appendLine("const-string v1, \"$safeKey\"")
                                sb.appendLine("invoke-virtual {p0, v1}, Lcom/revenuecat/purchases/EntitlementInfos;->get(Ljava/lang/String;)Lcom/revenuecat/purchases/EntitlementInfo;")
                                sb.appendLine("move-result-object v2")
                                sb.appendLine("if-eqz v2, :skip_rc_$idx")
                                sb.appendLine("invoke-static {v2}, Lcom/revenuecat/purchases/hybridcommon/mappers/EntitlementInfoMapperKt;->map(Lcom/revenuecat/purchases/EntitlementInfo;)Ljava/util/Map;")
                                sb.appendLine("move-result-object v2")
                                sb.appendLine("invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                                sb.appendLine(":skip_rc_$idx")
                                idx++
                            }
                            sb.appendLine("new-instance v1, Ljava/util/HashMap;")
                            sb.appendLine("invoke-direct {v1}, Ljava/util/HashMap;-><init>()V")
                            sb.appendLine("const-string v2, \"all\"")
                            sb.appendLine("invoke-virtual {v1, v2, v0}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v2, \"active\"")
                            sb.appendLine("invoke-virtual {v1, v2, v0}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("const-string v2, \"verification\"")
                            sb.appendLine("const-string v0, \"VERIFIED\"")
                            sb.appendLine("invoke-virtual {v1, v2, v0}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                            sb.appendLine("return-object v1")

                            runCatching {
                                method.addInstructions(0, sb.toString())
                                logger.info("[X-Ray RevenueCat] Hooked EntitlementInfosMapperKt.map with ${entitlementKeys.size} keys!")
                            }.onFailure { e ->
                                logger.info("[X-Ray RCMapper Error] ${e.message}")
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
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const-string v0, "ad_free"
                                        invoke-static {v0}, Ljava/util/Collections;->singleton(Ljava/lang/Object;)Ljava/util/Set;
                                        move-result-object v0
                                        return-object v0
                                    """
                                )
                            }
                        }
                    }
                }

                // 2. Hook AdInstanceManager (Ls9/a;) and FlutterAd subclasses (Ls9/*)
                if (isInUnmaskedFlutterAds) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0

                        // In AdInstanceManager.trackAd (x(Ls9/f;I)V), immediately queue onAdFailedToLoad (k(ILs9/f$c;)V)
                        if (flutterAdBaseType != null && flutterLoadAdErrorType != null && flutterResponseInfoType != null &&
                            method.parameterTypes.size == 2 &&
                            method.parameterTypes[0].toString() == flutterAdBaseType &&
                            method.parameterTypes[1] == "I" &&
                            method.returnType == "V"
                        ) {
                            val failMethod = mutableClass.methods.firstOrNull {
                                it.parameterTypes.size == 2 &&
                                    it.parameterTypes[0] == "I" &&
                                    it.parameterTypes[1].toString() == flutterLoadAdErrorType &&
                                    it.returnType == "V"
                            }
                            val insns = method.implementation?.instructions
                            val returnIdx = insns?.size?.minus(1) ?: -1
                            logger.info("[X-Ray TrackAd] Found trackAd ${method.name} regCount=$regCount returnIdx=$returnIdx failMethod=${failMethod?.name}")
                            if (failMethod != null && returnIdx >= 0 && regCount >= 5) {
                                runCatching {
                                    method.addInstructions(
                                        returnIdx,
                                        """
                                            new-instance v0, $flutterLoadAdErrorType
                                            const-string v1, "No fill"
                                            const/4 p1, 0x0
                                            invoke-direct {v0, p1, v1, v1, p1}, $flutterLoadAdErrorType-><init>(ILjava/lang/String;Ljava/lang/String;$flutterResponseInfoType)V
                                            invoke-virtual {p0, p2, v0}, $type->${failMethod.name}(I$flutterLoadAdErrorType)V
                                        """
                                    )
                                    logger.info("[X-Ray Bytecode] Hooked AdInstanceManager.trackAd ($type->${method.name}) to fire ${failMethod.name}!")
                                }.onFailure { e ->
                                    logger.info("[X-Ray TrackAd Error] ${e.message}")
                                }
                            }
                        }

                        // Block load() methods (d/e/h) on FlutterAd subclasses, leaving dispose (b) intact
                        val superCls = mutableClass.superclass ?: ""
                        if (flutterAdBaseType != null &&
                            (superCls == flutterAdBaseType || superCls.startsWith(flutterAdBaseType.removeSuffix(";") + "$") ||
                                (superCls.startsWith(type.substringBeforeLast('/') + "/") && superCls != "Ljava/lang/Object;"))
                        ) {
                            if ((method.name == "d" || method.name == "e" || method.name == "h") &&
                                method.parameterTypes.isEmpty() &&
                                method.returnType == "V" &&
                                regCount >= 1
                            ) {
                                runCatching {
                                    method.addInstructions(0, "return-void")
                                    logger.info("[X-Ray Bytecode] Blocked FlutterAd load $type->${method.name}()V")
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

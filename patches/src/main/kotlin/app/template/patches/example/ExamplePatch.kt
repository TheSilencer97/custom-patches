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
    "pro", "premium", "ad_free", "adfree", "ad-free", "no_ads", "noads",
    "remove_ads", "removeads", "Remove ads", "Ad-Free", "Ad-Free Experience",
    "ad_free_experience", "liquipedia_ad_free", "liquipedia_pro", "turbo",
    "plus", "supporter", "vip", "default", "entitlement", "subscription"
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

private val adEnableGetterRegex = Regex(
    "(?i)^(is|are|should|can|has|get)(Ad|Ads|Banner|Banners|Dfp|Gam|Mopub|Commercial|Advertising)(Enabled|Visible|Available|Shown|Active|Allowed|Supported|On)?$" +
        "|(?i)^(showAds|enableAds|adsEnabled|hasAds|canShowAds|shouldShowAds)$"
)

private val adFreeGetterRegex = Regex(
    "(?i)^(isAdFree|isAdsDisabled|areAdsDisabled|areAdsRemoved|isNoAds|getAdFree|isPremium|isSubscribed|isPro|hasActiveSubscription)$"
)

private fun isAdRelated(text: String): Boolean {
    if (text.isEmpty()) return false
    val cleaned = text.substringAfterLast('/')
    return adTokenRegex.containsMatchIn(cleaned) ||
        text.contains("com.google.android.gms.ads", ignoreCase = true) ||
        text.contains("io.flutter.plugins.googlemobileads", ignoreCase = true) ||
        text.contains("com.yandex.mobile.ads", ignoreCase = true) ||
        text.contains("com.applovin.mediation.ads", ignoreCase = true) ||
        text.contains("com.mopub.mobileads", ignoreCase = true)
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
                        val matches = Regex("[A-Za-z0-9_\\-.:/ ]{4,60}").findAll(text).map { it.value }.toList()
                        for (idx in matches.indices) {
                            val s = matches[idx]
                            if (s.contains("Ad-Free", true) || s.contains("ad_free", true) ||
                                s.contains("remove_ads", true) || s.contains("entitlement", true) ||
                                s.startsWith("goog_")
                            ) {
                                val win = matches.subList((idx - 3).coerceAtLeast(0), (idx + 4).coerceAtMost(matches.size))
                                logger.info("[X-Ray DartString] Near '$s': $win")
                                win.map { it.trim() }.filter { it.length in 3..35 && !it.contains(" ") && !it.contains("/") }
                                    .forEach { entitlementKeys.add(it) }
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
        val discoveredAdLoaderClasses = mutableSetOf("Lcom/google/android/gms/ads/AdLoader;")
        val discoveredAdRequestClasses = mutableSetOf("Lcom/google/android/gms/ads/AdRequest;")
        val discoveredAdMobPackages = mutableSetOf<String>()
        val discoveredFlutterAdsPackages = mutableSetOf<String>()
        var obfAdListenerType: String? = null
        var obfLoadAdErrorType: String? = null

        classDefForEach { classDef ->
            val type = classDef.type
            if (type == "Lcom/google/ads/mediation/AbstractAdViewAdapter;") {
                for (m in classDef.methods) {
                    if (m.name == "requestBannerAd" && m.parameterTypes.size >= 4) {
                        val adSizeType = m.parameterTypes[3].toString()
                        discoveredAdSizeClasses.add(adSizeType)
                        discoveredAdMobPackages.add(adSizeType.substringBeforeLast('/') + "/")
                    } else if (m.name == "newAdLoader") {
                        val builderType = m.returnType
                        discoveredAdLoaderClasses.add(builderType.substringBefore('$') + ";")
                    } else if (m.name == "buildAdRequest") {
                        discoveredAdRequestClasses.add(m.returnType)
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
            if (type.startsWith("Lcom/revenuecat/")) {
                targetClasses.add(type)
                val methods = classDef.methods.joinToString(", ") { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
                logger.info("[X-Ray RevenueCat] $type -> $methods")
            }
        }

        classDefForEach { classDef ->
            val type = classDef.type
            val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
            val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

            if (isInUnmaskedAdMob && classDef.superclass == "Landroid/view/ViewGroup;") {
                val getListener = classDef.methods.firstOrNull { it.name == "getAdListener" }
                if (getListener != null) obfAdListenerType = getListener.returnType
            }

            if (isInUnmaskedAdMob || isInUnmaskedFlutterAds ||
                discoveredAdSizeClasses.contains(type) ||
                discoveredAdLoaderClasses.contains(type) ||
                adSizeClassRegex.matches(type) ||
                adViewClassRegex.matches(type) ||
                adSdkPackageRegex.matches(type)
            ) {
                targetClasses.add(type)
            } else if (!type.startsWith("Landroid/") && !type.startsWith("Landroidx/") &&
                !type.startsWith("Lkotlin/") && !type.startsWith("Ljava/")
            ) {
                val simpleName = type.substringAfterLast('/').removeSuffix(";")
                if (isAdRelated(simpleName)) {
                    targetClasses.add(type)
                } else if (classDef.methods.any {
                        it.parameterTypes.isEmpty() && it.returnType == "Z" &&
                            (adEnableGetterRegex.matches(it.name) || adFreeGetterRegex.matches(it.name))
                    }
                ) {
                    targetClasses.add(type)
                }
            }
        }

        if (obfAdListenerType != null) {
            classDefForEach { classDef ->
                if (classDef.type == obfAdListenerType) {
                    val errMethod = classDef.methods.firstOrNull { it.parameterTypes.size == 1 && it.returnType == "V" }
                    if (errMethod != null) obfLoadAdErrorType = errMethod.parameterTypes[0].toString()
                }
            }
        }

        for (type in targetClasses) {
            runCatching {
                val mutableClass = mutableClassDefBy(type)
                val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
                val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

                // 1. Bridge RevenueCat EntitlementInfos.all & active maps for Flutter
                if (type == "Lcom/revenuecat/purchases/EntitlementInfos;") {
                    val mapFields = mutableClass.fields.filter { it.type == "Ljava/util/Map;" }.map { it.name }
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name == "<init>" &&
                            method.parameterTypes.size == 2 &&
                            method.parameterTypes[0] == "Ljava/util/Map;" &&
                            regCount in 4..15 &&
                            mapFields.isNotEmpty()
                        ) {
                            val returnIdx = method.implementation?.instructions?.indexOfLast {
                                it.opcode.name == "RETURN_VOID"
                            } ?: -1
                            if (returnIdx >= 0) {
                                val sb = StringBuilder()
                                sb.appendLine("new-instance v0, Ljava/util/LinkedHashMap;")
                                sb.appendLine("invoke-direct {v0}, Ljava/util/LinkedHashMap;-><init>()V")
                                var idx = 0
                                for (key in entitlementKeys) {
                                    val safeKey = key.replace("\"", "")
                                    sb.appendLine("const-string p1, \"$safeKey\"")
                                    sb.appendLine("invoke-virtual {p0, p1}, Lcom/revenuecat/purchases/EntitlementInfos;->get(Ljava/lang/String;)Lcom/revenuecat/purchases/EntitlementInfo;")
                                    sb.appendLine("move-result-object p2")
                                    sb.appendLine("if-eqz p2, :skip_rc_$idx")
                                    sb.appendLine("invoke-virtual {v0, p1, p2}, Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                                    sb.appendLine(":skip_rc_$idx")
                                    idx++
                                }
                                for (fName in mapFields) {
                                    sb.appendLine("iput-object v0, p0, Lcom/revenuecat/purchases/EntitlementInfos;->$fName:Ljava/util/Map;")
                                }
                                runCatching {
                                    method.addInstructions(returnIdx, sb.toString())
                                    logger.info("[X-Ray RevenueCat] Populated EntitlementInfos maps ($mapFields) with ${entitlementKeys.size} keys")
                                }
                            }
                        }
                    }
                } else if (type == "Lcom/revenuecat/purchases/CustomerInfo;") {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if ((method.name == "getActiveSubscriptions" || method.name == "getAllPurchasedProductIdentifiers") &&
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
                                logger.info("[X-Ray RevenueCat] Populated CustomerInfo->${method.name}")
                            }
                        }
                    }
                }

                // 2. Zero out AdSize (including 1-arg Context pixel getters & Lz3/c0 helpers)
                if (discoveredAdSizeClasses.contains(type) || adSizeClassRegex.matches(type)) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.returnType == "I" && method.name != "hashCode" &&
                            (method.parameterTypes.isEmpty() ||
                                (method.parameterTypes.size == 1 && method.parameterTypes[0] == "Landroid/content/Context;")) &&
                            regCount >= 1
                        ) {
                            runCatching {
                                method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                                logger.info("[X-Ray Bytecode] Zeroed AdSize $type->${method.name}")
                            }
                        } else if (method.name == "<init>" &&
                            method.parameterTypes.size >= 2 &&
                            method.parameterTypes[0] == "I" &&
                            method.parameterTypes[1] == "I" &&
                            regCount in 3..15
                        ) {
                            runCatching {
                                method.addInstructions(0, "const/4 p1, 0x0\nconst/4 p2, 0x0")
                            }
                        }
                    }
                }

                if (isInUnmaskedAdMob) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        // Zero out Lz3/c0;->a(Lz3/i;)I and b(Lz3/i;)I
                        if (method.parameterTypes.size == 1 &&
                            discoveredAdSizeClasses.contains(method.parameterTypes[0].toString()) &&
                            method.returnType == "I" && regCount >= 1
                        ) {
                            runCatching {
                                method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                                logger.info("[X-Ray Bytecode] Zeroed AdSize helper $type->${method.name}")
                            }
                        }
                        // In BaseAdView (Lz3/m;), trigger onAdFailedToLoad when loadAd (b(Lz3/h;)V) is called
                        val takesAdRequest = method.parameterTypes.any { discoveredAdRequestClasses.contains(it.toString()) }
                        if (takesAdRequest && method.returnType == "V" && method.name != "<init>" && regCount in 2..15) {
                            if (mutableClass.superclass == "Landroid/view/ViewGroup;" &&
                                obfAdListenerType != null && obfLoadAdErrorType != null
                            ) {
                                runCatching {
                                    method.addInstructions(
                                        0,
                                        """
                                            invoke-virtual {p0}, $type->getAdListener()$obfAdListenerType
                                            move-result-object v0
                                            if-eqz v0, :done_fail
                                            const/4 p1, 0x0
                                            invoke-virtual {v0, p1}, $obfAdListenerType->h($obfLoadAdErrorType)V
                                            :done_fail
                                            return-void
                                        """
                                    )
                                    logger.info("[X-Ray Bytecode] Triggered onAdFailedToLoad in $type->${method.name}")
                                }
                            } else {
                                runCatching {
                                    method.addInstructions(0, "return-void")
                                }
                            }
                        }
                    }
                }

                // 3. In FlutterAdsPkg (Ls9/*): allow messenger to run & trigger immediate onAdFailedToLoad
                if (isInUnmaskedFlutterAds) {
                    val intFields = mutableClass.fields.filter { it.type == "I" }.map { it.name }
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0

                        // Make FlutterLoadAdError.<init>(Lz3/o;)V accept null safely
                        if (method.name == "<init>" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0].toString() == obfLoadAdErrorType &&
                            regCount in 4..15
                        ) {
                            val fourArgInit = mutableClass.methods.firstOrNull {
                                it.name == "<init>" && it.parameterTypes.size == 4 && it.parameterTypes[0] == "I"
                            }
                            if (fourArgInit != null) {
                                val lastParamType = fourArgInit.parameterTypes[3]
                                runCatching {
                                    method.addInstructions(
                                        0,
                                        """
                                            const/4 v0, 0x3
                                            const-string v1, "No fill"
                                            const/4 p1, 0x0
                                            invoke-direct {p0, v0, v1, v1, p1}, $type-><init>(ILjava/lang/String;Ljava/lang/String;$lastParamType)V
                                            return-void
                                        """
                                    )
                                    logger.info("[X-Ray Bytecode] Made FlutterLoadAdError null-safe in $type")
                                }
                            }
                        }

                        // Zero out FlutterAdSize (Ls9/n;) int fields (width/height) at end of constructors
                        if (method.name == "<init>" &&
                            intFields.size == 2 &&
                            ( (method.parameterTypes.size == 2 && method.parameterTypes[0] == "I" && method.parameterTypes[1] == "I") ||
                                (method.parameterTypes.size == 1 && discoveredAdSizeClasses.contains(method.parameterTypes[0].toString())) ) &&
                            regCount in 2..15
                        ) {
                            val returnIdx = method.implementation?.instructions?.indexOfLast {
                                it.opcode.name == "RETURN_VOID"
                            } ?: -1
                            if (returnIdx >= 0) {
                                val sb = StringBuilder("const/4 v0, 0x0\n")
                                for (f in intFields) {
                                    sb.appendLine("iput v0, p0, $type->$f:I")
                                }
                                runCatching {
                                    method.addInstructions(returnIdx, sb.toString())
                                    logger.info("[X-Ray Bytecode] Zeroed FlutterAdSize fields in $type")
                                }
                            }
                        }

                        // In FlutterAdLoader (Ls9/i;), immediately fire onAdFailedToLoad for Native & Banner ads
                        if (obfAdListenerType != null && obfLoadAdErrorType != null &&
                            method.returnType == "V" && method.name != "<init>" && regCount <= 15
                        ) {
                            val listenerIdx = method.parameterTypes.indexOfFirst { it.toString() == obfAdListenerType }
                            if (listenerIdx >= 0) {
                                val pReg = "p${listenerIdx + 1}"
                                runCatching {
                                    method.addInstructions(
                                        0,
                                        """
                                            if-eqz $pReg, :done_native_fail
                                            const/4 p1, 0x0
                                            invoke-virtual {$pReg, p1}, $obfAdListenerType->h($obfLoadAdErrorType)V
                                            :done_native_fail
                                            return-void
                                        """
                                    )
                                    logger.info("[X-Ray Bytecode] Triggered Native onAdFailedToLoad in $type->${method.name}")
                                }
                            } else if (method.parameterTypes.size >= 2 && method.parameterTypes[0] == "Ljava/lang/String;") {
                                runCatching {
                                    method.addInstructions(0, "return-void")
                                }
                            }
                        }
                    }
                }

                // 4. Neutralize AdLoader (Lz3/g;)
                if (discoveredAdLoaderClasses.contains(type) && !type.contains('$')) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name != "<init>" && method.name != "<clinit>" && method.returnType == "V" && regCount >= 1) {
                            runCatching { method.addInstructions(0, "return-void") }
                        }
                    }
                }

                // 5. Block standard named ad load methods
                if (adSdkPackageRegex.matches(type) || adViewClassRegex.matches(type) ||
                    type == "Lcom/google/ads/mediation/AbstractAdViewAdapter;"
                ) {
                    for (method in mutableClass.methods) {
                        if (adLoadMethodRegex.matches(method.name) && method.returnType == "V" &&
                            (method.implementation?.registerCount ?: 0) >= 1
                        ) {
                            runCatching { method.addInstructions(0, "return-void") }
                        }
                    }
                }

                // 6. Collapse Android View onMeasure & setVisibility
                if (adViewClassRegex.matches(type) || adSdkPackageRegex.matches(type) || isInUnmaskedAdMob) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name == "onMeasure" && method.parameterTypes.size == 2 &&
                            method.parameterTypes[0] == "I" && method.parameterTypes[1] == "I" &&
                            method.returnType == "V" && regCount in 3..15
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    "const/4 v0, 0x0\ninvoke-virtual {p0, v0, v0}, Landroid/view/View;->setMeasuredDimension(II)V\nreturn-void"
                                )
                            }
                        } else if (method.name == "setVisibility" && method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "I" && method.returnType == "V" && regCount in 2..15
                        ) {
                            runCatching { method.addInstructions(0, "const/16 p1, 0x8") }
                        }
                    }
                }

                // 7. Flip boolean ad/pro getters
                for (method in mutableClass.methods) {
                    val regCount = method.implementation?.registerCount ?: 0
                    if (method.parameterTypes.isEmpty() && method.returnType == "Z" && regCount >= 1) {
                        if (adEnableGetterRegex.matches(method.name)) {
                            runCatching { method.addInstructions(0, "const/4 v0, 0x0\nreturn v0") }
                        } else if (adFreeGetterRegex.matches(method.name)) {
                            runCatching { method.addInstructions(0, "const/4 v0, 0x1\nreturn v0") }
                        }
                    }
                }
            }
        }
    }
}

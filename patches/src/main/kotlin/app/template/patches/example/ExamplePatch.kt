package app.morphe.patches.example

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.util.logging.Logger

private val logger = Logger.getLogger("CollapseAdSpace")

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
    val zeroAttrs = listOf(
        "android:padding", "android:paddingTop", "android:paddingBottom",
        "android:paddingLeft", "android:paddingRight", "android:paddingStart", "android:paddingEnd",
        "android:paddingVertical", "android:paddingHorizontal",
        "android:layout_margin", "android:layout_marginTop", "android:layout_marginBottom",
        "android:layout_marginLeft", "android:layout_marginRight", "android:layout_marginStart", "android:layout_marginEnd",
        "android:layout_marginVertical", "android:layout_marginHorizontal"
    )
    for (attr in zeroAttrs) {
        if (el.hasAttribute(attr)) {
            el.setAttribute(attr, "0.0dip")
        }
    }
}

private val collapseAdResourcesPatch = resourcePatch {
    execute {
        val candidatePaths = mutableSetOf<String>()

        // Discover on-demand decodable resource paths in Morphe 2.1.0+
        runCatching {
            for (field in this::class.java.declaredFields) {
                field.isAccessible = true
                val v = field.get(this)
                when (v) {
                    is Collection<*> -> v.forEach { item ->
                        val s = item?.toString() ?: ""
                        if (s.startsWith("res/layout") || s.startsWith("res/values")) {
                            candidatePaths.add(s)
                        }
                    }
                    is Map<*, *> -> v.keys.forEach { k ->
                        val s = k?.toString() ?: ""
                        if (s.startsWith("res/layout") || s.startsWith("res/values")) {
                            candidatePaths.add(s)
                        }
                    }
                }
            }
        }

        val rootDir = get(".")
        rootDir.walkTopDown().filter {
            it.isFile && it.extension.equals("xml", ignoreCase = true)
        }.forEach { file ->
            candidatePaths.add(file.relativeTo(rootDir).invariantSeparatorsPath)
        }

        logger.info("[X-Ray Resources] Found ${candidatePaths.size} candidate XML paths")

        for (relPath in candidatePaths) {
            val fileName = relPath.substringAfterLast('/')
            val parentName = relPath.substringBeforeLast('/', "").substringAfterLast('/').lowercase()

            if (parentName.startsWith("values") && fileName.startsWith("dimens")) {
                runCatching {
                    document(relPath).use { doc ->
                        val dimens = doc.getElementsByTagName("dimen")
                        for (i in 0 until dimens.length) {
                            val item = dimens.item(i) as? Element ?: continue
                            val name = item.getAttribute("name")
                            if (isAdRelated(name)) {
                                val oldVal = item.textContent
                                item.textContent = "0.0dip"
                                logger.info("[X-Ray Dimen] $relPath: $name ($oldVal -> 0.0dip)")
                            }
                        }
                    }
                }
            } else if (parentName.startsWith("layout")) {
                val fileNameNoExt = fileName.substringBeforeLast('.')
                val isAdFile = isAdRelated(fileNameNoExt)

                runCatching {
                    document(relPath).use { doc ->
                        val rootEl = doc.documentElement ?: return@use
                        if (isAdFile) {
                            logger.info("[X-Ray Layout File] Collapsing entire layout: $relPath")
                            collapseElement(rootEl)
                            val all = rootEl.getElementsByTagName("*")
                            for (i in 0 until all.length) {
                                val child = all.item(i) as? Element ?: continue
                                collapseElement(child)
                            }
                        } else {
                            val all = doc.getElementsByTagName("*")
                            val toCollapse = mutableListOf<Element>()
                            for (i in 0 until all.length) {
                                val el = all.item(i) as? Element ?: continue
                                val tag = el.tagName ?: ""
                                val id = el.getAttribute("android:id")
                                val cls = el.getAttribute("class")
                                val layoutRef = el.getAttribute("layout")

                                if (isAdRelated(tag) || isAdRelated(id) || isAdRelated(cls) || isAdRelated(layoutRef)) {
                                    toCollapse.add(el)
                                    logger.info("[X-Ray Layout Node] $relPath: <$tag> id=$id layout=$layoutRef")
                                }
                            }
                            for (el in toCollapse) {
                                collapseElement(el)
                                val parent = el.parentNode as? Element
                                if (parent != null && parent != rootEl) {
                                    val parentId = parent.getAttribute("android:id")
                                    var childElementCount = 0
                                    val childNodes = parent.childNodes
                                    for (j in 0 until childNodes.length) {
                                        if (childNodes.item(j) is Element) childElementCount++
                                    }
                                    if (childElementCount <= 2 || isAdRelated(parentId) || isAdRelated(parent.tagName)) {
                                        collapseElement(parent)
                                        logger.info("[X-Ray Parent Node] $relPath: collapsed parent <${parent.tagName}> id=$parentId")
                                    }
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
    description = "Collapses empty ad banners, placeholders, Flutter ads, and obfuscated AdMob containers across layouts and code.",
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
        val nativeAdFactoryClasses = mutableSetOf<String>()

        // Pass 1: Unmask R8-obfuscated AdMob & Flutter GoogleMobileAds packages
        classDefForEach { classDef ->
            val type = classDef.type

            if (type == "Lcom/google/ads/mediation/AbstractAdViewAdapter;") {
                for (m in classDef.methods) {
                    if (m.name == "requestBannerAd" && m.parameterTypes.size >= 4) {
                        val adSizeType = m.parameterTypes[3].toString()
                        discoveredAdSizeClasses.add(adSizeType)
                        val pkg = adSizeType.substringBeforeLast('/') + "/"
                        discoveredAdMobPackages.add(pkg)
                        logger.info("[X-Ray Unmask] Unmasked AdSize=$adSizeType, AdMobPkg=$pkg")
                    } else if (m.name == "newAdLoader") {
                        val builderType = m.returnType
                        val loaderType = builderType.substringBefore('$') + ";"
                        discoveredAdLoaderClasses.add(loaderType)
                        discoveredAdLoaderClasses.add(builderType)
                        logger.info("[X-Ray Unmask] Unmasked AdLoader=$loaderType")
                    } else if (m.name == "buildAdRequest") {
                        val reqType = m.returnType
                        discoveredAdRequestClasses.add(reqType)
                        logger.info("[X-Ray Unmask] Unmasked AdRequest=$reqType")
                    }
                }
            }

            // Detect NativeAdFactory implementation (like Liquipedia's b class) to unmask Flutter GoogleMobileAds
            val hasNativeAdViewReturn = classDef.methods.any { m ->
                m.returnType == "Lcom/google/android/gms/ads/nativead/NativeAdView;"
            }
            if (hasNativeAdViewReturn && !type.startsWith("Lcom/google/")) {
                nativeAdFactoryClasses.add(type)
                targetClasses.add(type)
                for (iface in classDef.interfaces) {
                    val pkg = iface.substringBeforeLast('/') + "/"
                    if (!pkg.startsWith("Ljava/") && !pkg.startsWith("Landroid/")) {
                        discoveredFlutterAdsPackages.add(pkg)
                        logger.info("[X-Ray Unmask] Unmasked FlutterAdsPkg=$pkg from $type interface $iface")
                    }
                }
            }
        }

        // Pass 2: Collect all target classes using unmasked packages
        classDefForEach { classDef ->
            val type = classDef.type

            val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
            val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

            if (isInUnmaskedAdMob || isInUnmaskedFlutterAds ||
                discoveredAdSizeClasses.contains(type) ||
                discoveredAdLoaderClasses.contains(type) ||
                adSizeClassRegex.matches(type) ||
                adViewClassRegex.matches(type) ||
                adSdkPackageRegex.matches(type)
            ) {
                targetClasses.add(type)
                if (isInUnmaskedFlutterAds) {
                    val methodList = classDef.methods.joinToString(", ") {
                        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                    }
                    logger.info("[X-Ray FlutterAdsClass] $type (super=${classDef.superclass}, ifaces=${classDef.interfaces}) -> $methodList")
                } else if (isInUnmaskedAdMob) {
                    val methodList = classDef.methods.joinToString(", ") {
                        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                    }
                    logger.info("[X-Ray ObfAdMobClass] $type (super=${classDef.superclass}) -> $methodList")
                }
            } else if (!type.startsWith("Landroid/") &&
                !type.startsWith("Landroidx/") &&
                !type.startsWith("Lkotlin/") &&
                !type.startsWith("Lkotlinx/") &&
                !type.startsWith("Ljava/") &&
                !type.startsWith("Ljavax/") &&
                !type.startsWith("Lcom/google/common/")
            ) {
                val simpleName = type.substringAfterLast('/').removeSuffix(";")
                if (isAdRelated(simpleName)) {
                    targetClasses.add(type)
                } else {
                    val hasAdGetter = classDef.methods.any { m ->
                        m.parameterTypes.isEmpty() && m.returnType == "Z" &&
                            (adEnableGetterRegex.matches(m.name) || adFreeGetterRegex.matches(m.name))
                    }
                    if (hasAdGetter) {
                        targetClasses.add(type)
                    }
                }
            }
        }

        // Pass 3: Apply bytecode modifications
        for (type in targetClasses) {
            runCatching {
                val mutableClass = mutableClassDefBy(type)
                val isInUnmaskedAdMob = discoveredAdMobPackages.any { type.startsWith(it) }
                val isInUnmaskedFlutterAds = discoveredFlutterAdsPackages.any { type.startsWith(it) }

                // 1. Zero out AdSize (both standard and R8-obfuscated like Lz3/i;)
                if (discoveredAdSizeClasses.contains(type) || adSizeClassRegex.matches(type)) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.parameterTypes.isEmpty() &&
                            method.returnType == "I" &&
                            method.name != "hashCode" &&
                            regCount >= 1
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Zeroed AdSize $type->${method.name}()I")
                            }
                        } else if (method.name == "<init>" &&
                            method.parameterTypes.size >= 2 &&
                            method.parameterTypes[0] == "I" &&
                            method.parameterTypes[1] == "I" &&
                            regCount in 3..15
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 p1, 0x0
                                        const/4 p2, 0x0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Zeroed AdSize constructor $type-><init>")
                            }
                        }
                    }
                }

                // 2. Neutralize AdLoader (like Lz3/g;) so native/banner ads never load
                if (discoveredAdLoaderClasses.contains(type) && !type.contains('$')) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name != "<init>" &&
                            method.name != "<clinit>" &&
                            method.returnType == "V" &&
                            regCount >= 1
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        return-void
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Blocked AdLoader $type->${method.name}")
                            }
                        }
                    }
                }

                // 3. Neutralize classes inside unmasked AdMob package (like Lz3/*)
                if (isInUnmaskedAdMob) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        val takesAdRequest = method.parameterTypes.any { discoveredAdRequestClasses.contains(it.toString()) }
                        if (takesAdRequest && method.returnType == "V" && method.name != "<init>" && regCount >= 1) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        return-void
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Blocked AdRequest loader $type->${method.name}")
                            }
                        }
                    }
                }

                // 4. Neutralize unmasked Flutter GoogleMobileAds package
                if (isInUnmaskedFlutterAds) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        // If a class in FlutterAds has <init>(II...)V, it's FlutterAdSize
                        if (method.name == "<init>" &&
                            method.parameterTypes.size >= 2 &&
                            method.parameterTypes[0] == "I" &&
                            method.parameterTypes[1] == "I" &&
                            regCount in 3..15
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 p1, 0x0
                                        const/4 p2, 0x0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Zeroed FlutterAdSize $type-><init>")
                            }
                        } else if (method.name != "<init>" &&
                            method.name != "<clinit>" &&
                            method.parameterTypes.isEmpty() &&
                            method.returnType == "V" &&
                            regCount >= 1
                        ) {
                            // Blocks FlutterBannerAd.load()V, FlutterNativeAd.load()V, etc.
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        return-void
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Blocked FlutterAd 0-arg void $type->${method.name}()V")
                            }
                        } else if (method.parameterTypes.isEmpty() &&
                            method.returnType == "Landroid/view/View;" &&
                            regCount >= 1
                        ) {
                            // PlatformView.getView() -> return null so Flutter mounts nothing
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return-object v0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Nulled Flutter PlatformView $type->${method.name}()Landroid/view/View;")
                            }
                        }
                    }
                }

                // 5. Block standard named ad load methods & AbstractAdViewAdapter requests
                if (adSdkPackageRegex.matches(type) ||
                    adViewClassRegex.matches(type) ||
                    type == "Lcom/google/ads/mediation/AbstractAdViewAdapter;"
                ) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (adLoadMethodRegex.matches(method.name) &&
                            method.returnType == "V" &&
                            regCount >= 1
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        return-void
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Blocked $type->${method.name}")
                            }
                        }
                    }
                }

                // 6. Collapse Android View onMeasure & setVisibility on AdViews (including NativeAdView & Lz3/*)
                if (adViewClassRegex.matches(type) || adSdkPackageRegex.matches(type) || isInUnmaskedAdMob) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if (method.name == "onMeasure" &&
                            method.parameterTypes.size == 2 &&
                            method.parameterTypes[0] == "I" &&
                            method.parameterTypes[1] == "I" &&
                            method.returnType == "V" &&
                            regCount in 3..15
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        invoke-virtual {p0, v0, v0}, Landroid/view/View;->setMeasuredDimension(II)V
                                        return-void
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Collapsed onMeasure in $type")
                            }
                        } else if (method.name == "setVisibility" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == "I" &&
                            method.returnType == "V" &&
                            regCount in 2..15
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/16 p1, 0x8
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Forced GONE in $type->setVisibility")
                            }
                        }
                    }
                }

                // 7. Flip boolean ad/pro getters
                for (method in mutableClass.methods) {
                    val regCount = method.implementation?.registerCount ?: 0
                    if (method.parameterTypes.isEmpty() && method.returnType == "Z" && regCount >= 1) {
                        if (adEnableGetterRegex.matches(method.name)) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Forced false on $type->${method.name}()Z")
                            }
                        } else if (adFreeGetterRegex.matches(method.name)) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x1
                                        return v0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Forced true on $type->${method.name}()Z")
                            }
                        }
                    }
                }
            }
        }
    }
}

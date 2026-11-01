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
    "^(load|loadAd|loadAds|loadBanner|loadBannerAd|loadNativeAd|loadInterstitialAd|loadRewardedAd|loadRewardedInterstitialAd|loadAppOpenAd|loadAdManagerBannerAd|loadAdManagerInterstitialAd|loadFluidAd|onAdLoaded|show|showAd|showInterstitial)$"
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
        runCatching {
            val decodeMethod = this::class.java.methods.firstOrNull { it.name == "decode" }
            decodeMethod?.invoke(this, arrayOf("res/layout*/*.xml", "res/values*/*.xml"))
        }

        val rootDir = get(".")
        val xmlFiles = rootDir.walkTopDown().filter {
            it.isFile && it.extension.equals("xml", ignoreCase = true)
        }.toList()

        for (file in xmlFiles) {
            val relPath = file.relativeTo(rootDir).invariantSeparatorsPath
            val parentName = file.parentFile?.name?.lowercase() ?: ""

            if (parentName.startsWith("values") && file.name.startsWith("dimens")) {
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                if (!raw.contains("ad", ignoreCase = true) &&
                    !raw.contains("banner", ignoreCase = true) &&
                    !raw.contains("dfp", ignoreCase = true) &&
                    !raw.contains("mopub", ignoreCase = true)
                ) continue

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
                val fileNameNoExt = file.nameWithoutExtension
                val isAdFile = isAdRelated(fileNameNoExt)
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                val mightHaveAd = isAdFile ||
                    raw.contains("ad", ignoreCase = true) ||
                    raw.contains("banner", ignoreCase = true) ||
                    raw.contains("dfp", ignoreCase = true) ||
                    raw.contains("mopub", ignoreCase = true) ||
                    raw.contains("mrec", ignoreCase = true)

                if (!mightHaveAd) continue

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
    description = "Collapses empty ad banners, placeholders, Flutter ads, and containers across layouts and code.",
    default = false,
) {
    dependsOn(collapseAdResourcesPatch)

    execute {
        val targetClasses = mutableListOf<String>()

        classDefForEach { classDef ->
            val type = classDef.type
            val interfaces = classDef.interfaces

            if (type == "Lio/flutter/plugins/GeneratedPluginRegistrant;" ||
                interfaces.contains("Lio/flutter/embedding/engine/plugins/FlutterPlugin;") ||
                interfaces.contains("Lio/flutter/plugin/platform/PlatformViewFactory;")
            ) {
                val methodList = classDef.methods.joinToString(", ") {
                    "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                }
                logger.info("[X-Ray FlutterPlugin] $type (super=${classDef.superclass}, interfaces=$interfaces) -> $methodList")
            }

            if (adSizeClassRegex.matches(type) ||
                adViewClassRegex.matches(type) ||
                adSdkPackageRegex.matches(type)
            ) {
                targetClasses.add(type)
                if (type.startsWith("Lio/flutter/plugins/googlemobileads/") ||
                    type.startsWith("Lcom/yandex/mobile/ads/")
                ) {
                    logger.info("[X-Ray AdSDK] $type")
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
                if (isAdRelated(simpleName) || type.contains("liquipedia", ignoreCase = true)) {
                    targetClasses.add(type)
                    val methodList = classDef.methods.joinToString(", ") {
                        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                    }
                    logger.info("[X-Ray Class] $type (super=${classDef.superclass}) -> $methodList")
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

        for (type in targetClasses.distinct()) {
            runCatching {
                val mutableClass = mutableClassDefBy(type)

                // 1. Zero out AdSize / FlutterAdSize / BannerAdSize dimensions
                if (adSizeClassRegex.matches(type)) {
                    for (method in mutableClass.methods) {
                        val regCount = method.implementation?.registerCount ?: 0
                        if ((method.name == "getHeight" || method.name == "getHeightInPixels" ||
                                method.name == "getWidth" || method.name == "getWidthInPixels") &&
                            method.returnType == "I" && regCount >= 1
                        ) {
                            runCatching {
                                method.addInstructions(
                                    0,
                                    """
                                        const/4 v0, 0x0
                                        return v0
                                    """
                                )
                                logger.info("[X-Ray Bytecode] Zeroed $type->${method.name}")
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
                                logger.info("[X-Ray Bytecode] Zeroed constructor width/height in $type")
                            }
                        }
                    }
                }

                // 2. Block ad loading and onAdLoaded callbacks in Ad SDKs & Flutter GoogleMobileAds
                if (adSdkPackageRegex.matches(type) || adViewClassRegex.matches(type)) {
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

                // 3. Collapse Android View onMeasure & setVisibility on AdViews
                if (adViewClassRegex.matches(type) || adSdkPackageRegex.matches(type)) {
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

                // 4. Flip boolean ad/pro getters
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

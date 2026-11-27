package me.kofua.qmhelper

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import dalvik.system.BaseDexClassLoader
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kofua.qmhelper.XposedInit.Companion.modulePath
import me.kofua.qmhelper.data.*
import me.kofua.qmhelper.utils.*
import me.kofua.qmhelper.utils.BannerTips
import org.json.JSONObject
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.time.measureTimedValue

infix fun Class.from(classLoader: ClassLoader) =
    if (name.isNotEmpty()) name.from(classLoader) else null

val Member.paramTypes: Array<String>
    get() = when (this) {
        is Method -> parameterTypes.map { it.name }.toTypedArray()
        is Constructor<*> -> parameterTypes.map { it.name }.toTypedArray()
        else -> arrayOf()
    }

val qmPackage get() = QMPackage.instance
val hookInfo get() = QMPackage.instance.hookInfo

class QMPackage private constructor() {

    val bannerTipsClass by Weak { hookInfo.bannerTips.clazz from classLoader }
    val storageUtilsClass by Weak { hookInfo.storageUtils.clazz from classLoader }
    val eKeyManagerClass by Weak { hookInfo.eKeyManager.clazz from classLoader }
    val eKeyDecryptorClass by Weak { hookInfo.eKeyDecryptor.clazz from classLoader }
    val vipDownloadHelperClass by Weak { hookInfo.vipDownloadHelper.clazz from classLoader }
    val skinManagerClass by Weak { hookInfo.skinManager.clazz from classLoader }
    val baseSettingFragmentClass by Weak { hookInfo.setting.baseSettingFragment.clazz from classLoader }
    val baseSettingPackClass by Weak { hookInfo.setting.baseSettingPack.clazz from classLoader }
    val baseSettingProviderClass by Weak { hookInfo.setting.baseSettingProvider.clazz from classLoader }
    val settingClass by Weak { hookInfo.setting.clazz from classLoader }
    val switchListenerClass by Weak { hookInfo.setting.switchListener.clazz from classLoader }
    val jsonObjectClass by Weak { hookInfo.gson.jsonObject from classLoader }
    val appStarterActivityClass by Weak { hookInfo.appStarterActivity.clazz from classLoader }
    val storageVolumeClass by Weak { hookInfo.storageVolume from classLoader }
    val webRequestHeadersClass by Weak { hookInfo.webRequestHeaders.clazz from classLoader }
    val x5WebViewClass by Weak { "com.tencent.smtt.sdk.WebView" from classLoader }
    val userManagerClass by Weak { hookInfo.userManager.clazz from classLoader }
    val mERJniClass by Weak { "com.tencent.qqmusic.modular.framework.encrypt.logic.MERJni" from classLoader }
    val baseFragmentClass by Weak { hookInfo.baseFragment.clazz from classLoader }

    val hookInfo = run {
        val (result, time) = measureTimedValue { readHookInfo() }
        Log.d("Load hook info took $time")
        mainScope.launch(Dispatchers.IO) {
            result.toJson()?.let {
                JSONObject(it).toString(2)
            }.let { Log.d("Loaded hook info:\n$it") }
        }
        result
    }

    private fun readHookInfo(): HookInfo {
        try {
            val hookInfoFile = File(currentContext.cacheDir, HOOK_INFO_FILE_NAME)
            Log.d("Reading hook info: $hookInfoFile")
            val start = System.currentTimeMillis()
            if (hookInfoFile.isFile && hookInfoFile.canRead()) {
                val lastUpdateTime = getPackageLastUpdateTime(hostPackageName)
                val lastModuleUpdateTime = File(modulePath).lastModified()
                val info = hookInfoFile.inputStream().use {
                    runCatchingOrNull { ObjectInputStream(it).readAny<HookInfo>() } ?: HookInfo()
                }
                // 结构完整性校验：升级模块（新增 HookInfo 字段）后，旧缓存反序列化回来
                // 新字段全是默认值 —— 必须作废重建，否则新功能"看起来没生效"。
                // 校验 1.2.0 新增的 nativeUi；宿主没有 BaseRecyclerSettingFragment 时视为不适用。
                val nativeUiClassExists =
                    "com.tencent.qqmusic.fragment.debug.BaseRecyclerSettingFragment"
                        .from(currentContext.classLoader) != null
                val cacheStructurallyValid =
                    !nativeUiClassExists || info.setting.nativeUi.fragmentClass.isNotEmpty()
                if (cacheStructurallyValid &&
                    info.lastUpdateTime >= max(lastUpdateTime, lastModuleUpdateTime) &&
                    getVersionCode(hostPackageName) == info.clientVersionCode &&
                    BuildConfig.VERSION_CODE == info.moduleVersionCode &&
                    BuildConfig.VERSION_NAME == info.moduleVersionName
                ) {
                    val end = System.currentTimeMillis()
                    Log.d("Read hook info from cache success, took ${end - start} ms")
                    return info
                }
            }
        } catch (e: Throwable) {
            Log.w(e)
        }
        return initHookInfo().also {
            try {
                val hookInfoFile = File(currentContext.cacheDir, HOOK_INFO_FILE_NAME)
                if (hookInfoFile.exists()) hookInfoFile.delete()
                hookInfoFile.outputStream().use { o -> ObjectOutputStream(o).writeObject(it) }
            } catch (e: Exception) {
                Log.e(e)
            }
        }
    }

    companion object {
        val instance by lazy { QMPackage() }
        private const val HOOK_INFO_FILE_NAME = "hookinfo.dat"

        @JvmStatic
        fun initHookInfo() = HookInfo().apply out@{
            val classLoader = currentContext.classLoader
            val dexClassLoaderDelegate = { delegator: BaseDexClassLoader ->
                if (delegator.javaClass != PathClassLoader::class.java) {
                    delegator.getFirstFieldByExactTypeOrNull<BaseDexClassLoader>() ?: delegator
                } else delegator
            }
            //val classesList = classLoader.allClassesList(dexClassLoaderDelegate).asSequence()

            // 1.1.4 那版在这里 System.loadLibrary("qmhelper") 挂自研 JNI（DexBuilder 引擎）。
            // 现在 dex 索引换成 DexKit（libdexkit.so），加载由 DexHelper / NativeLib 按绝对路径
            // 自行处理 —— 宿主进程里 System.loadLibrary 根本找不到模块 APK 里的 so。
            val dexClassLoader =
                classLoader.findDexClassLoader(dexClassLoaderDelegate) ?: return@out
            val dexHelper = DexHelper(dexClassLoader)
            lastUpdateTime = max(
                getPackageLastUpdateTime(hostPackageName),
                File(modulePath).lastModified()
            )
            clientVersionCode = getVersionCode(hostPackageName)
            moduleVersionCode = BuildConfig.VERSION_CODE
            moduleVersionName = BuildConfig.VERSION_NAME
            baseFragment = BaseFragment().apply {
                val resumeMethod = dexHelper.findMethodUsingString(
                    "[onResume]this[%s]", true
                ).firstOrNull()?.run {
                    dexHelper.findMethodInvoking(
                        this,
                        parameterShorty = "V",
                        findFirst = false
                    ).asSequence().firstNotNullOfOrNull {
                        dexHelper.decodeMethodIndex(it)?.takeIf { m -> m.isAbstract }
                    }
                } ?: return@apply
                clazz = clazz { name = resumeMethod.declaringClass.name }
                resume = method {
                    name = resumeMethod.name
                    paramTypes = resumeMethod.paramTypes
                }
            }
            splashAdapter = clazz {
                val splashAdapterClass =
                    "com.tencentmusic.ad.adapter.madams.splash.OperExpertSplashAdapter"
                        .from(classLoader) ?: dexHelper.findMethodUsingString(
                        "postSplashResult", true
                    ).asSequence().firstNotNullOfOrNull {
                        dexHelper.decodeMethodIndex(it)
                    }?.declaringClass ?: return@clazz
                name = splashAdapterClass.name
            }
            homePageFragment = HomePageFragment().apply {
                // 21.x 首页 = business/timeline/ui/HomePageFragment（老 fragment.homepage 包已没了）。
                // 类名未混淆，直接按名取；它的 tab 方法在 QMPackage 侧不用解析，
                // tab 过滤的全部逻辑在 homeTopTab 里。
                clazz = clazz {
                    name = "com.tencent.qqmusic.business.timeline.ui.HomePageFragment"
                        .from(classLoader)?.name ?: return@clazz
                }
            }
            homeTopTab = HomeTopTab().apply {
                // 过滤闸门：HomePageFragment#h5(x0):Boolean —— 每个 tab 进列表前都要过它。
                // 反编译核对：Z6→j5→h5，h5 返回 false 宿主就不加这个 tab。
                val homeFragmentClass = "com.tencent.qqmusic.business.timeline.ui.HomePageFragment"
                    .from(classLoader) ?: return@apply
                val x0Class = "com.tencent.qqmusic.fragment.mainpage.data.x0"
                    .from(classLoader) ?: return@apply
                val addTabMethod = homeFragmentClass.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.size == 1 && m.parameterTypes[0] == x0Class &&
                            m.returnType == Boolean::class.javaPrimitiveType
                } ?: return@apply
                // x0#h():String = tabId（"6"=乐馆、"10"=直播），x0#f():Int = nativeLoadId。
                val tabIdMethod = x0Class.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.isEmpty() && m.returnType == String::class.java && m.name == "h"
                } ?: return@apply
                val nativeIdMethod = x0Class.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.isEmpty() && m.returnType == Int::class.javaPrimitiveType && m.name == "f"
                }
                clazz = clazz { name = homeFragmentClass.name }
                addTab = method {
                    name = addTabMethod.name
                    paramTypes = addTabMethod.parameterTypes.map { it.name }.toTypedArray()
                }
                tabId = method { name = tabIdMethod.name }
                nativeIdMethod?.let { nativeId = method { name = it.name } }
                Log.d("homeTopTab 解析完成：h5=${addTabMethod.name} tabId=${tabIdMethod.name} nativeId=${nativeIdMethod?.name ?: "-"}")
            }
            adManager = AdManager().apply {
                val onStartIndex = dexHelper.encodeMethodIndex(
                    "com.tencent.qqmusic.activity.AppStarterActivity".from(classLoader)
                        ?.getDeclaredMethod("onStart") ?: return@apply
                )
                val getMethod = dexHelper.findMethodInvoking(
                    onStartIndex,
                    parameterShorty = "L",
                    findFirst = false
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it)?.takeIf { m ->
                        m is Method && m.isNotStatic
                    }
                } ?: return@apply
                clazz = clazz { name = getMethod.declaringClass.name }
                get = method { name = getMethod.name }
            }
            val settingBuilderClass = dexHelper.findMethodUsingString(
                "onSwitchLister can't be null while type is TYPE_SWITCH"
            ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass
            val settingClass = settingBuilderClass?.declaringClass
            setting = Setting().apply {
                // 21.0.5.3 的设置框架（非混淆包名，全部可直读）：
                //   settings.base.Setting            条目模型（type/titleText/subRightDesc/…）
                //   settings.base.Setting$Builder    构造器（type/title/subRightDesc/click/build）
                //   settings.base.SettingPackage     包接口（createSettingProvider）
                //   settings.base.BaseSettingPackage 包实现（settings:List / settingProviders:List / notifyEvent）
                //   settings.packages.b              抽屉(更多设置)包，刷新方法里有 refreshSettingList: FORCE REFRESH
                //
                // 旧版锚点（DebugSettingFragment / PersonalCenterEntryView / BaseSettingFragment）全部消失，
                // 所以这里不再依赖它们：只解 Setting / Builder / 抽屉包刷新方法，够入口注入 + 面板渲染用。
                val settingBuilderClass = dexHelper
                    .findMethodUsingString("onSwitchLister can't be null while type is TYPE_SWITCH")
                    .firstOrNull()?.declaringClass
                    ?: "com.tencent.qqmusic.fragment.morefeatures.settings.base.Setting\$Builder".from(classLoader)
                    ?: return@apply
                val settingClass = settingBuilderClass.declaringClass ?: return@apply
                val withMethod = settingClass.declaredMethods.find {
                    it.isStatic && it.returnType == settingBuilderClass
                } ?: return@apply
                val fields = settingClass.declaredFields
                fun namedField(vararg names: String): me.kofua.qmhelper.data.Field =
                    names.firstNotNullOfOrNull { n -> fields.find { it.name == n } }
                        ?.let { f -> field { name = f.name } } ?: defField

                clazz = clazz { name = settingClass.name }
                with = method { name = withMethod.name }
                type = namedField("type")
                title = namedField("titleText", "title")
                // 21.x 右侧灰字叫 subRightDesc（旧版是 rightDesc）
                rightDesc = namedField("subRightDesc", "rightDesc")
                redDotListener = namedField("redDotListener")

                builder = SettingBuilder().apply {
                    clazz = clazz { name = settingBuilderClass.name }
                    build = method { name = settingBuilderClass.declaredMethods
                        .firstOrNull { it.returnType == settingClass }?.name ?: "build" }
                }
                // 宿主的开关回调接口（SettingView$d）：只有两个方法，名字未混淆
                switchListener = SwitchListener().apply {
                    clazz = clazz {
                        name = "com.tencent.qqmusic.fragment.morefeatures.settings.view.SettingView\$d"
                            .from(classLoader)?.name
                            ?: "com.tencent.qqmusic.fragment.morefeatures.settings.view.SettingView\$d"
                    }
                    isSwitchOn = method { name = "isSwitchOn" }
                    onSwitchStatusChange = method { name = "onSwitchStatusChange" }
                }

                // 抽屉设置包（我的 → 更多设置）：入口注入点在它的刷新方法上。
                dexHelper.findMethodUsingString("refreshSettingList: FORCE REFRESH").firstOrNull()
                    ?.let { refreshMethod ->
                        drawerSettingPack = DrawerSettingPack().apply {
                            clazz = clazz { name = refreshMethod.declaringClass.name }
                            refresh = method {
                                name = refreshMethod.name
                                paramTypes = refreshMethod.parameterTypes.map { it.name }.toTypedArray()
                            }
                            createSettingProvider = method {
                                name = refreshMethod.declaringClass.methods
                                    .firstOrNull { m -> m.returnType == CopyOnWriteArrayList::class.java }?.name
                                    ?: "createSettingProvider"
                            }
                        }
                    }
                // 原生设置页（模块自己的页面，长得和 Q音 设置一模一样）：
                // 21.x 的框架还在 —— BaseRecyclerSettingFragment（title/settingPackage 抽象方法）
                // + BaseSettingPackage（createSettingProvider）。类名/方法名都没混淆，直接按名取。
                val baseRecyclerFragmentClass =
                    "com.tencent.qqmusic.fragment.debug.BaseRecyclerSettingFragment".from(classLoader)
                val baseSettingPackageClass =
                    "com.tencent.qqmusic.fragment.morefeatures.settings.base.BaseSettingPackage".from(classLoader)
                if (baseRecyclerFragmentClass != null && baseSettingPackageClass != null) {
                    nativeUi = NativeSettingUi().apply {
                        fragmentClass = baseRecyclerFragmentClass.name
                        packageClass = baseSettingPackageClass.name
                        // 抽象方法名未混淆，核对过 descriptor：
                        //   title():String / settingPackage():BaseSettingPackage / createSettingProvider():CopyOnWriteArrayList
                        titleMethod = "title"
                        settingPackageMethod = "settingPackage"
                        createSettingProviderMethod = baseSettingPackageClass.methods
                            .firstOrNull { m -> CopyOnWriteArrayList::class.java.isAssignableFrom(m.returnType) }
                            ?.name ?: "createSettingProvider"
                    }
                    Log.d("原生设置页：BaseRecyclerSettingFragment / BaseSettingPackage 就绪")
                } else {
                    Log.d("原生设置页：宿主框架类缺失，入口将退回自绘 Dialog")
                }
            }
            personalEntryView = PersonalEntryView().apply {
                val entryViewClass =
                    "com.tencent.qqmusic.fragment.morefeatures.settings.view.PersonalCenterEntryView"
                        .from(classLoader) ?: dexHelper.findMethodUsingString(
                        "PersonalCenterEntryView",
                    ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                        ?.declaringClass ?: return@apply
                val updateMethod = entryViewClass.declaredMethods.find {
                    it.isSynthetic && it.parameterTypes.size == 1
                } ?: return@apply
                val rightDescViewField = entryViewClass.declaredFields.find {
                    TextView::class.java.isAssignableFrom(it.type)
                } ?: return@apply
                val redDotViewField = entryViewClass.declaredFields.find {
                    ImageView::class.java.isAssignableFrom(it.type)
                } ?: return@apply
                clazz = clazz { name = entryViewClass.name }
                update = method {
                    name = updateMethod.name
                    paramTypes = updateMethod.paramTypes
                }
                rightDescView = field { name = rightDescViewField.name }
                redDotView = field { name = redDotViewField.name }
            }
            bannerTips = me.kofua.qmhelper.data.BannerTips().apply {
                val showStyledToastMethod = dexHelper.findMethodUsingString(
                    "showToast() >>> IS USING QPLAY_AUTO, FORBID BANNER_TIPS",
                    parameterCount = 7,
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it)
                } ?: return@apply
                clazz = clazz { name = showStyledToastMethod.declaringClass.name }
                showStyledToast = method { name = showStyledToastMethod.name }
            }
            settingFragment = SettingFragment().apply {
                val settingFragmentClass =
                    "com.tencent.qqmusic.fragment.morefeatures.SettingFeaturesFragment"
                        .from(classLoader) ?: dexHelper.findMethodUsingString(
                        "KEY_SETTING_JUMP_ITEM_ENUM",
                        parameterCount = 1,
                    ).asSequence().firstNotNullOfOrNull {
                        dexHelper.decodeMethodIndex(it)
                    }?.declaringClass ?: return@apply
                val settingListField = settingFragmentClass
                    .findFieldByExactType(List::class.java) ?: return@apply
                clazz = clazz { name = settingFragmentClass.name }
                resume = baseFragment.resume
                settingList = field { name = settingListField.name }
            }
            userInfoHolder = UserInfoHolder().apply {
                // 方法名 showTaskAndVipBubble 未混淆，直接按名取。
                val showBubbleMethod =
                    "com.tencent.qqmusic.modular.module.musichall.views.viewholders.cell.MyMusicBubbleViewHolder"
                        .from(classLoader)?.declaredMethods?.find {
                            it.name == "showTaskAndVipBubble"
                        } ?: run {
                        // 兜底：老链路（refreshVipIcons 宿主 + 短签名 VL 的 final 方法）
                        val clazz = dexHelper.findMethodUsingString("refreshVipIcons")
                            .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                            ?.declaringClass ?: return@apply
                        val clazzIndex = dexHelper.encodeClassIndex(clazz)
                        dexHelper.findMethodUsingString(
                            "3",
                            parameterShorty = "VL",
                            declaringClass = clazzIndex,
                            findFirst = false
                        ).asSequence().firstNotNullOfOrNull {
                            dexHelper.decodeMethodIndex(it)?.takeIf { m -> m.isFinal }
                        }
                    } ?: return@apply
                clazz = clazz { name = showBubbleMethod.declaringClass.name }
                showBubble = method {
                    name = showBubbleMethod.name
                    paramTypes = showBubbleMethod.paramTypes
                }
            }
            strategyModule = StrategyModule().apply {
                val getStrategyIdMethod = dexHelper.findMethodUsingString(
                    "[getClientStrategyStrategyId]: this:"
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = getStrategyIdMethod.declaringClass.name }
                getStrategyId = method {
                    name = getStrategyIdMethod.name
                    paramTypes = getStrategyIdMethod.paramTypes
                }
            }
            topAreaDelegate = TopAreaDelegate().apply {
            topAreaDelegate = TopAreaDelegate().apply {
                // 21.x：initLiveGuide 搬到了 playernew/view/live/g0（老的 topAreaDelegate 类没了）。
                // 锚点字符串 "[initLiveGuide]" 还活着（两个方法共用，取第一个即可——都是同类引导）。
                val initLiveGuideMethod = dexHelper.findMethodUsingString("[initLiveGuide]")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                // showCurrentListen 的长锚点在 21.x 已消失；当前收听入口挪进了 currentlisten 包，
                // 保守起见不再解析（对应功能降级为"只关直播引导"）。
                val showCurListenMethod: java.lang.reflect.Member? = null
                val clazz = initLiveGuideMethod.declaringClass
                val classIndex = dexHelper.encodeClassIndex(clazz)
                val showShareGuideMethod = dexHelper.findMethodUsingString(
                    "ACTION_QQFRIEND",
                    declaringClass = classIndex,
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it)
                } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                initLiveGuide = method {
                    name = initLiveGuideMethod.name
                    paramTypes = initLiveGuideMethod.paramTypes
                }
                showCurListenMethod?.let {
                    showCurListen = method {
                        name = it.name
                        paramTypes = it.paramTypes
                    }
                }
                showShareGuide = method {
                    name = showShareGuideMethod.name
                    paramTypes = showShareGuideMethod.paramTypes
                }
            }
            playViewModel = PlayerViewModel().apply {
                val clazz = "com.tencent.qqmusic.business.playernew.viewmodel.PlayerViewModel"
                    .from(classLoader) ?: dexHelper.findMethodUsingString("PlayerViewModel")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass
                ?: return@apply
                val setCanSlideMethod = clazz.declaredMethods.find { m ->
                    !m.isSynthetic && m.returnType == Void.TYPE
                            && m.parameterTypes.let { it.size == 1 && it[0] == clazz }
                } ?: return@apply
                val postCanSlideMethod = dexHelper.encodeMethodIndex(setCanSlideMethod).run {
                    dexHelper.findMethodInvoking(this, parameterShorty = "VZ")
                        .asSequence().firstNotNullOfOrNull { dexHelper.decodeMethodIndex(it) }
                } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                postCanSlide = method { name = postCanSlideMethod.name }
                setCanSlide = method {
                    name = setCanSlideMethod.name
                    paramTypes = setCanSlideMethod.paramTypes
                }
            }
            spManager = SpManager().apply {
                val clazz = dexHelper.findMethodUsingString("SPManager").firstOrNull()
                    ?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass ?: return@apply
                val getMethod = clazz.declaredMethods.find {
                    it.isStatic && it.returnType == clazz && it.parameterTypes.isEmpty()
                } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                get = method { name = getMethod.name }
            }
            appStarterActivity = AppStarterActivity().apply {
                val doOnCreateMethod = dexHelper
                    .findMethodUsingString("Appstarter activity oncreate")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?: return@apply
                val addSecondFragmentMethod = dexHelper
                    .findMethodUsingString("[addSecondFragment] gotoFirstFragment").firstOrNull()
                    ?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                val appStarterActivityClass = doOnCreateMethod.declaringClass
                val showMessageDialogMethod = appStarterActivityClass.methods.find { m ->
                    Dialog::class.java.isAssignableFrom(m.returnType) && m.parameterTypes.let {
                        it.size == 8 && it[0] == String::class.java && it[7] == Boolean::class.javaPrimitiveType
                    }
                } ?: return@apply
                clazz = clazz { name = appStarterActivityClass.name }
                doOnCreate = method {
                    name = doOnCreateMethod.name
                    paramTypes = doOnCreateMethod.paramTypes
                }
                addSecondFragment = method { name = addSecondFragmentMethod.name }
                showMessageDialog = method { name = showMessageDialogMethod.name }
            }
            modeFragment = clazz {
                // 「简洁模式 / 运行模式」设置页。
                // 21.x 换了包名与类名：morefeatures.mode.ModeSettingFragmentNew（旧版是
                // morefeatures.ModeSettingFragment，锚点字符串 runningModeSetting 也已消失）。
                val candidates = arrayOf(
                    "com.tencent.qqmusic.fragment.morefeatures.mode.ModeSettingFragmentNew",
                    "com.tencent.qqmusic.fragment.morefeatures.ModeSettingFragment",
                )
                Log.d("modeFragment 候选：" + candidates.joinToString { "$it=" + (it.from(classLoader)?.name ?: "-") })
                name = candidates.firstNotNullOfOrNull { it.from(classLoader)?.name }
                    ?: dexHelper.findMethodUsingString("runningModeSetting").firstOrNull()
                        ?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass?.name
                    ?: dexHelper.findMethodUsingString("ModeSettingFragmentNew").firstOrNull()
                        ?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass?.name
                    ?: return@clazz
            }
            authAgent = AuthAgent().apply {
                val startActionActivityMethod = dexHelper.findMethodUsingString(
                    "LOGIN_CHECK_SDK"
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = startActionActivityMethod.declaringClass.name }
                startActionActivity = method {
                    name = startActionActivityMethod.name
                    paramTypes = startActionActivityMethod.paramTypes
                }
            }
            val parseModuleItemMethod = dexHelper.findMethodUsingString(
                "[parseModuleItem] "
            ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) as? Method }
            jsonRespParser = JsonRespParser().apply {
                parseModuleItemMethod ?: return@apply
                clazz = clazz { name = parseModuleItemMethod.declaringClass.name }
                parseModuleItem = method {
                    name = parseModuleItemMethod.name
                    paramTypes = parseModuleItemMethod.paramTypes
                }
            }
            gson = Gson().apply {
                val gsonClass = "com.google.gson.Gson".from(classLoader)
                    ?: dexHelper.findMethodUsingString("GSON cannot serialize ").firstOrNull()
                        ?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass ?: return@apply
                val fromJsonMethod = gsonClass.declaredMethods.find { m ->
                    m.parameterTypes.let { it.size == 2 && it[0] == String::class.java && it[1] == java.lang.Class::class.java }
                } ?: return@apply
                val toJsonMethod = gsonClass.declaredMethods.find { m ->
                    m.parameterTypes.let { it.size == 1 && it[0] == Any::class.java } && m.returnType == String::class.java
                } ?: return@apply
                val jsonObjectClass = parseModuleItemMethod?.parameterTypes
                    ?.getOrNull(2) ?: return@apply
                clazz = clazz { name = gsonClass.name }
                fromJson = method { name = fromJsonMethod.name }
                toJson = method { name = toJsonMethod.name }
                jsonObject = clazz { name = jsonObjectClass.name }
            }
            uiModeManager = UiModeManager().apply {
                val clazz = dexHelper.findMethodUsingString("UIModeManager")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass
                    ?: return@apply
                val method = clazz.declaredMethods.find { m ->
                    m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.let { it.size == 1 && it[0] == String::class.java }
                } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                isThemeForbid = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            adBar = ApkDownloadAdBar().apply {
                val clazz = "com.tencent.qqmusic.business.ad.topbarad.apkdownload.ApkDownloadAdBar"
                    .from(classLoader) ?: dexHelper.findMethodUsingString("ApkDownloadAdBar")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass
                ?: return@apply
                val methods = clazz.interfaces.firstOrNull()?.methods?.filter {
                    it.returnType == Void.TYPE && it.parameterTypes.isEmpty()
                } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                this.methods = methods.map { method { name = it.name } }
            }
            eKeyManager = AudioStreamEKeyManager().apply {
                val getFileEKeyMethod = dexHelper.findMethodUsingString(
                    "getFileEKey filePath is empty!"
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?: return@apply
                val eKeyManagerClass = getFileEKeyMethod.declaringClass
                val instanceField = eKeyManagerClass.declaredFields.find {
                    it.type == eKeyManagerClass
                } ?: return@apply
                clazz = clazz { name = eKeyManagerClass.name }
                instance = field { name = instanceField.name }
                getFileEKey = method { name = getFileEKeyMethod.name }
            }
            eKeyDecryptor = EKeyDecryptor().apply {
                val decryptFileMethod = dexHelper.findMethodUsingString(
                    "decryptFile srcFilePath = "
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                val companionClass = decryptFileMethod.declaringClass
                val decryptorClass = companionClass.declaringClass ?: return@apply
                val instanceField = decryptorClass.declaredFields.find {
                    it.type == companionClass
                } ?: return@apply
                clazz = clazz { name = decryptorClass.name }
                instance = field { name = instanceField.name }
                decryptFile = method { name = decryptFileMethod.name }
            }
            vipDownloadHelper = VipDownloadHelper().apply {
                val decryptFileMethod = dexHelper.findMethodUsingString(
                    "decryptFile create dest = "
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = decryptFileMethod.declaringClass.name }
                decryptFile = method { name = decryptFileMethod.name }
            }
            bottomTipController = BottomTipController().apply {
                val method = dexHelper.findMethodUsingString("updateBottomTips ")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?: return@apply
                clazz = clazz { name = method.declaringClass.name }
                updateBottomTips = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            videoViewDelegate = VideoViewDelegate().apply {
                val method = dexHelper.findMethodUsingString("[onResult] show mv icon.")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?: return@apply
                clazz = clazz { name = method.declaringClass.name }
                onResult = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            genreViewDelegate = GenreViewDelegate().apply {
                val method = dexHelper.findMethodUsingString(
                    "[onBind] hide the song genre tags info."
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = method.declaringClass.name }
                onBind = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            userGuideViewDelegate = UserGuideViewDelegate().apply {
                val method = dexHelper.findMethodUsingString(
                    "showNewUserGuide hasGuideShowing ="
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?: return@apply
                clazz = clazz { name = method.declaringClass.name }
                showUserGuide = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            topSongViewDelegate = TopSongViewDelegate().apply {
                val method = dexHelper.findMethodUsingString(
                    "[onBind] show top song info: peakCount="
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?: return@apply
                clazz = clazz { name = method.declaringClass.name }
                onBind = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            dataPlugin = DataPlugin().apply {
                val handleJsRequestMethod = dexHelper.findMethodUsingString(
                    "[handleJsRequest] writeGlobalData"
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                val dataPluginClass = handleJsRequestMethod.declaringClass
                val basePluginClass = dataPluginClass.superclass ?: return@apply
                val runtimeField = basePluginClass.declaredFields
                    .find { it.isPublic } ?: return@apply
                val activityMethod = runtimeField.type.declaredMethods.find {
                    it.parameterTypes.isEmpty() && it.returnType == Activity::class.java
                } ?: return@apply
                clazz = clazz { name = dataPluginClass.name }
                handleJsRequest = method {
                    name = handleJsRequestMethod.name
                    paramTypes = handleJsRequestMethod.paramTypes
                }
                runtime = field { name = runtimeField.name }
                activity = method { name = activityMethod.name }
            }
            albumIntroViewHolder = AlbumIntroViewHolder().apply {
                val c = "com.tencent.qqmusic.albumdetail.ui.viewholders.AlbumIntroduceViewHolder"
                    .from(classLoader) ?: dexHelper.findMethodUsingString("tvAlbumDetail")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass
                ?: return@apply
                val m = c.declaredMethods.find { m ->
                    m.isPublic && m.returnType == Void.TYPE && m.parameterTypes
                        .let { it.size == 1 && it[0] == View::class.java }
                } ?: return@apply
                val tvAlbumDetailField = c.declaredFields.find {
                    it.isNotStatic && TextView::class.java.isAssignableFrom(it.type)
                } ?: return@apply
                val lastTextContentField = c.declaredFields.find {
                    it.isNotStatic && it.type == String::class.java
                } ?: return@apply
                clazz = clazz { name = c.name }
                onHolderCreated = method {
                    name = m.name
                    paramTypes = m.paramTypes
                }
                tvAlbumDetail = field { name = tvAlbumDetailField.name }
                lastTextContent = field { name = lastTextContentField.name }
            }
            albumTagViewHolder = AlbumTagViewHolder().apply {
                val clazz = "com.tencent.qqmusic.albumdetail.ui.viewholders.AlbumTagViewHolder"
                    .from(classLoader)?.name ?: dexHelper.findMethodUsingString("tvAlbumInfo")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?.declaringClass?.name ?: return@apply
                this.clazz = clazz { name = clazz }
                onHolderCreated = albumIntroViewHolder.onHolderCreated
            }
            settingView = SettingView().apply {
                val clazz = "com.tencent.qqmusic.fragment.morefeatures.settings.view.SettingView"
                    .from(classLoader) ?: dexHelper.findMethodUsingString(
                    "initView: titleText "
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    ?.declaringClass ?: return@apply
                val method = clazz.declaredMethods.find { m ->
                    m.parameterTypes.let { it.size == 1 && it[0] == settingClass }
                } ?: return@apply
                val setLastClickTimeMethod = clazz.declaredMethods.find { m ->
                    m.isSynthetic && m.parameterTypes.let { it.size == 2 && it[1] == Long::class.javaPrimitiveType }
                } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                setSetting = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
                setLastClickTime = method {
                    name = setLastClickTimeMethod.name
                    paramTypes = setLastClickTimeMethod.paramTypes
                }
            }
            fileUtils = FileUtils().apply {
                val getSongNameIndex = dexHelper.findMethodUsingString(
                    "[getDownloadSongName] songInfo is null"
                ).firstOrNull() ?: return@apply
                val stringIndex = dexHelper.encodeClassIndex(String::class.java)
                val toValidFilenameMethod = dexHelper.findMethodInvoking(
                    getSongNameIndex,
                    returnType = stringIndex,
                    parameterTypes = arrayOf(stringIndex, stringIndex, stringIndex),
                ).asSequence().firstNotNullOfOrNull {
                    dexHelper.decodeMethodIndex(it)
                } ?: return@apply
                clazz = clazz { name = toValidFilenameMethod.declaringClass.name }
                toValidFilename = method {
                    name = toValidFilenameMethod.name
                    paramTypes = toValidFilenameMethod.paramTypes
                }
            }
            storageVolume = clazz {
                name = "com.tencent.qqmusiccommon.storage.StorageVolume".from(classLoader)?.name
                    ?: dexHelper.findMethodUsingString("StorageVolume [mStorageId=")
                        .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                        ?.declaringClass?.name ?: return@clazz
            }
            storageUtils = StorageUtils().apply {
                val getVolumesMethod = dexHelper.findMethodUsingString(
                    "StorageVolume From Android API SDK_INT : "
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = getVolumesMethod.declaringClass.name }
                getVolumes = method {
                    name = getVolumesMethod.name
                    paramTypes = getVolumesMethod.paramTypes
                }
            }
            vipAdBarData = clazz {
                name = dexHelper.findMethodUsingString("VipAdBarData(posId=").firstOrNull()
                    ?.let { dexHelper.decodeMethodIndex(it) }?.declaringClass?.name ?: return@clazz
            }
            skinManager = SkinManager().apply {
                val getSkinIdMethod = dexHelper.findMethodUsingString(
                    "[getSyncSkinIdInUse][event:has not update skinIdInUse,use SkinIdToSwitch = %s]"
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = getSkinIdMethod.declaringClass.name }
                getSkinId = method { name = getSkinIdMethod.name }
            }
            adResponseData = AdResponseData().apply {
                val classes = dexHelper.findMethodUsingString(
                    "AdResponseData(retCode=",
                    findFirst = false,
                ).map { dexHelper.decodeMethodIndex(it)?.declaringClass }
                    .filterNotNull().ifEmpty { return@apply }
                val getAdsMethods = classes.map { c ->
                    c.declaredMethods.filter { it.returnType == List::class.java && it.parameterTypes.isEmpty() }
                }
                classes.zip(getAdsMethods) { c, ml ->
                    AdResponseDataItem().apply {
                        clazz = clazz { name = c.name }
                        getAds = ml.map { method { name = it.name } }
                    }
                }.let { item = it }
            }
            jceRespConverter = JceResponseConverter().apply {
                // 同一个字符串在 21.x 里有两处引用：真正的 JCE 响应转换器（参数是 Object, Class）
                // 和 safefeedback 里一个无关的强转日志。这里按参数签名挑掉后者（否则 hook 会抛 ClassCastException）。
                val parseMethod = dexHelper.findMethodUsingString(
                    "JceResponseItemConverter",
                    findFirst = false,
                ).firstOrNull { m ->
                    m.parameterTypes.size == 2 && m.parameterTypes[1] == Class::class.java
                } ?: return@apply
                parse = method {
                    name = parseMethod.name
                    paramTypes = parseMethod.paramTypes
                }
            }
            webRequestHeaders = WebRequestHeaders().apply {
                val getUAMethod = dexHelper.findMethodUsingString(
                    " ReleasedForAndroid["
                ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                val clazz = getUAMethod.declaringClass
                val getCookiesMethod = clazz.declaredMethods.find { m ->
                    m.returnType == String::class.java && m.parameterTypes.let { it.size == 1 && it[0] == String::class.java }
                } ?: return@apply
                val instanceField = clazz.declaredFields.find { it.type == clazz } ?: return@apply
                this.clazz = clazz { name = clazz.name }
                instance = field { name = instanceField.name }
                getCookies = method { name = getCookiesMethod.name }
                getUA = method { name = getUAMethod.name }
            }
            userManager = UserManager().apply {
                val candidates = dexHelper.findMethodUsingString(
                    "getMusicUin",
                    matchPrefix = true,
                    returnType = String::class.java,
                    parameterCount = 0,
                    findFirst = false,
                )
                for (getMusicUinMethod in candidates) {
                    val owner = getMusicUinMethod.declaringClass
                    val getMethod = owner.declaredMethods.firstOrNull {
                        it.isStatic && it.returnType == owner && it.parameterTypes.isEmpty()
                    } ?: continue
                    val isLoginMethod = dexHelper.findMethodUsingString(
                        "isLogin",
                        matchPrefix = true,
                        declaringClass = owner,
                        returnType = Boolean::class.javaPrimitiveType,
                        parameterCount = 0,
                    ).firstOrNull() ?: continue
                    clazz = clazz { name = owner.name }
                    get = method { name = getMethod.name }
                    getMusicUin = method { name = getMusicUinMethod.name }
                    isLogin = method { name = isLoginMethod.name }
                    break
                }
            }
            bannerManager = BannerManager().apply {
                val method = dexHelper.findMethodUsingString("[requestAdvertisement] start")
                    .firstOrNull()?.let { dexHelper.decodeMethodIndex(it) } ?: return@apply
                clazz = clazz { name = method.declaringClass.name }
                requestAd = method {
                    name = method.name
                    paramTypes = method.paramTypes
                }
            }
            musicWorldPullEntrance = MusicWorldPullEntrance().apply {
                var clazz =
                    "com.tencent.qqmusic.fragment.mymusic.my.musicworld.MusicWorldPullEntrance"
                        .from(classLoader)
                val onClickListenerClass = View.OnClickListener::class.java
                var showButtonMethod: Member? = clazz?.declaredMethods?.find { m ->
                    m.parameterTypes.let { it.size == 1 && it[0] == onClickListenerClass }
                            && m.returnType == Void.TYPE
                }
                if (showButtonMethod == null) {
                    showButtonMethod = dexHelper.findMethodUsingString(
                        "[showMusicWorldEntranceBtn]",
                        parameterTypes = arrayOf(dexHelper.encodeClassIndex(onClickListenerClass))
                    ).firstOrNull()?.let { dexHelper.decodeMethodIndex(it) }
                    clazz = showButtonMethod?.declaringClass ?: return@apply
                }
                clazz?.let { this.clazz = clazz { name = it.name } }
                showButton = method {
                    name = showButtonMethod.name
                    paramTypes = showButtonMethod.paramTypes
                }
            }

            dexHelper.close()
        }
    }
}
}

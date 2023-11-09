@file:Suppress("UNCHECKED_CAST")

package moe.fuqiuluo.unidbg.env

import com.github.unidbg.linux.android.dvm.*
import com.github.unidbg.linux.android.dvm.array.ArrayObject
import com.tencent.mobileqq.channel.SsoPacket
import com.tencent.mobileqq.dt.model.FEBound
import com.tencent.mobileqq.qsec.qsecest.SelfBase64
import com.tencent.mobileqq.qsec.qsecurity.DeepSleepDetector
import com.tencent.mobileqq.sign.QQSecuritySign
import kotlinx.coroutines.sync.Mutex
import moe.fuqiuluo.comm.EnvData
import moe.fuqiuluo.ext.toHexString
import moe.fuqiuluo.unidbg.QSecVM
import moe.fuqiuluo.unidbg.vm.GlobalData
import net.mamoe.mirai.utils.MiraiLogger
import net.mamoe.mirai.utils.getRandomIntString
import top.mrxiaom.qsign.CommonConfig
import top.mrxiaom.qsign.QSignService
import top.mrxiaom.qsign.QSignService.Factory.Companion.CONFIG
import java.io.File
import java.security.SecureRandom
import java.util.*
import kotlin.random.Random
import kotlin.random.nextInt

private val logger = MiraiLogger.Factory.create(QSecJni::class.java)

typealias BytesObject = com.github.unidbg.linux.android.dvm.array.ByteArray

class QSecJni(
    val envData: EnvData,
    val vm: QSecVM,
    val global: GlobalData
) : AbstractJni() {
    private val androidVersion
        get() = CommonConfig.androidVersion
    private val androidSdkVersion
        get() = CommonConfig.androidSdkVersion
    private val targetSdkVersion
        get() = CommonConfig.targetSdkVersion
    private val storageSize
        get() = CommonConfig.storageSize
    override fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String): Int {
        if (signature == "android/os/Build\$VERSION->SDK_INT:I") {
            return androidSdkVersion
        }
        return super.getStaticIntField(vm, dvmClass, signature)
    }

    override fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Int {
        if (signature == "android/content/pm/ApplicationInfo->targetSdkVersion:I") {
            return targetSdkVersion
        }
        return super.getIntField(vm, dvmObject, signature)
    }

    override fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList) {
        if (signature == "com/tencent/mobileqq/fe/IFEKitLog->i(Ljava/lang/String;ILjava/lang/String;)V") {
            val tag = vaList.getObjectArg<StringObject>(0)
            val msg = vaList.getObjectArg<StringObject>(2)
            logger.verbose(tag.value + "info: " + msg.value)
            return
        }
        if (signature == "com/tencent/mobileqq/fe/IFEKitLog->e(Ljava/lang/String;ILjava/lang/String;)V") {
            val tag = vaList.getObjectArg<StringObject>(0)
            val msg = vaList.getObjectArg<StringObject>(2)
            logger.verbose(tag.value + "error: " + msg.value)
            return
        }
        if (signature == "com/tencent/mobileqq/channel/ChannelProxy->sendMessage(Ljava/lang/String;[BJ)V") {
            val cmd = vaList.getObjectArg<StringObject>(0).value
            val data = vaList.getObjectArg<BytesObject>(1).value
            val callbackId = vaList.getLongArg(2)
            val hex = data.toHexString()

            if (callbackId == -1L) return

            logger.verbose("uin = ${global["uin"]}, id = $callbackId, sendPacket(cmd = $cmd, data = $hex)")
            (global["PACKET"] as ArrayList<SsoPacket>).add(SsoPacket(cmd, hex, callbackId))
            (global["mutex"] as Mutex).also { if (it.isLocked) it.unlock() }
            return
        }

        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSec->updateO3DID(Ljava/lang/String;)V") {
            val o3did = vaList.getObjectArg<StringObject>(0).value
            global["o3did"] = o3did
            return
        }

        if (signature == "com/tencent/secprotocol/ByteData->putUping(IIILjava/lang/Object;)V") {
            return
        }

        super.callVoidMethodV(vm, dvmObject, signature, vaList)
    }

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSecConfig->business_uin:Ljava/lang/String;") {
            return StringObject(vm, global["uin"] as String)
        }
        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSecConfig->business_seed:Ljava/lang/String;") {
            return StringObject(vm, global["seed"] as? String ?: "")
        }
        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSecConfig->business_guid:Ljava/lang/String;") {
            return StringObject(vm, global["guid"] as? String ?: "")
        }
        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSecConfig->business_o3did:Ljava/lang/String;") {
            return StringObject(vm, global["o3did"] as? String ?: "")
        }
        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSecConfig->business_q36:Ljava/lang/String;") {
            return StringObject(vm, global["qimei36"] as? String ?: "")
        }
        if (signature == "com/tencent/mobileqq/qsec/qsecurity/QSecConfig->business_qua:Ljava/lang/String;") {
            return StringObject(vm, this.vm.envData.qua)
        }
        return super.getStaticObjectField(vm, dvmClass, signature)
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): DvmObject<*> {
        if (signature == "android/content/pm/ApplicationInfo->nativeLibraryDir:Ljava/lang/String;") {
            return StringObject(
                vm,
                "${FileResolver.getAppInstallFolder(envData.packageName)}/lib/arm64"
            )
        }
        return super.getObjectField(vm, dvmObject, signature)
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: DvmObject<*>) {
        if (signature == "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->token:[B") {
            val data = value.value as ByteArray
            (dvmObject as QQSecuritySign.SignResultObject).setToken(data)
            return
        }
        if (signature == "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->extra:[B") {
            val data = value.value as ByteArray
            (dvmObject as QQSecuritySign.SignResultObject).setExtra(data)
            return
        }
        if (signature == "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult->sign:[B") {
            val data = value.value as ByteArray
            (dvmObject as QQSecuritySign.SignResultObject).setSign(data)
            return
        }
        super.setObjectField(vm, dvmObject, signature, value)
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Int {
        if ("java/lang/String->hashCode()I" == signature) {
            return (dvmObject.value as String).hashCode()
        }
        return super.callIntMethodV(vm, dvmObject, signature, vaList)
    }

    override fun callStaticObjectMethodV(
        vm: BaseVM,
        dvmClass: DvmClass,
        signature: String,
        vaList: VaList
    ): DvmObject<*> {
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->mmKVValue(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(
                vm, when (val key = vaList.getObjectArg<StringObject>(0).value) {
                    "TuringRiskID-TuringCache-20230511" -> ""
                    "o3_switch_Xwid", "o3_xwid_switch" -> global["o3_switch_Xwid"] as? String ?: "1"
                    "DeviceToken-oaid-V001" -> ""
                    "DeviceToken-MODEL-XX-V001" -> ""
                    "DeviceToken-ANDROID-ID-V001" -> ""
                    "DeviceToken-qimei36-V001" -> global["qimei36"] as? String ?: ""
                    "MQQ_SP_DEVICETOKEN_DID_DEVICEIDUUID_202207072241" -> UUID.randomUUID()
                        .toString() + "|" + this.vm.envData.version

                    "DeviceToken-APN-V001", "DeviceToken-TuringCache-V001", "DeviceToken-MAC-ADR-V001", "DeviceToken-wifissid-V001" -> "-1"
                    else -> error("Not support mmKVValue:$key")
                }
            )
        }
        if (signature == "android/provider/Settings\$System->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;") {
            val key = vaList.getObjectArg<StringObject>(1).value
            if (key == "android_id") {
                return StringObject(vm, envData.androidId.lowercase())
            }
        }
        if (signature == "com/tencent/mobileqq/fe/utils/DeepSleepDetector->getCheckResult()Ljava/lang/String;") {
            val result = (global["DeepSleepDetector"] as DeepSleepDetector).getCheckResult()
            return StringObject(vm, result.toString())
        }
        if (signature == "com/tencent/mobileqq/dt/model/FEBound->transform(I[B)[B") {
            val mode = vaList.getIntArg(0)
            val data = vaList.getObjectArg<DvmObject<*>>(1).value as ByteArray
            val result = FEBound.transform(mode, data)
            if (mode == 1)
                logger.verbose("FEBound.transform(${data.toHexString()}) => ${result?.toHexString()}")
            return BytesObject(vm, result)
        }
        if (signature == "java/lang/ClassLoader->getSystemClassLoader()Ljava/lang/ClassLoader;") {
            return vm.resolveClass("java/lang/ClassLoader")
                .newObject(ClassLoader.getSystemClassLoader())
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->getPropSafe(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(
                vm, when (val key = vaList.getObjectArg<StringObject>(0).value) {
                    "ro.build.id" -> "TKQ1.221013.002"
                    "ro.build.keys" -> "test-keys"
                    "ro.build.display.id" -> "TKQ1.221013.002 test-keys"
                    "ro.product.device", "ro.product.name" -> "mondrian"
                    "ro.product.board" -> "taro"
                    "ro.product.manufacturer" -> "Xiaomi"
                    "ro.product.brand" -> "Redmi"
                    "ro.bootloader" -> "unknown"
                    "persist.sys.timezone" -> "Asia/Shanghai"
                    "ro.hardware" -> "qcom"
                    "ro.product.cpi.abi" -> "arm64-v8a"
                    "ro.product.cpu.abilist", "ro.system.product.cpu.abilist" -> "arm64-v8a,armeabi-v7a,armeabi"
                    "ro.product.cpu.abilist32", "ro.system.product.cpu.abilist32" -> "armeabi-v7a, armeabi"
                    "ro.product.cpu.abilist64", "ro.system.product.cpu.abilist64" -> "arm64-v8a"
                    "ro.build.version.incremental" -> "V14.0.8.0.TMQCNXM"
                    "ro.build.version.release" -> androidVersion
                    "ro.build.version.sdk" -> androidSdkVersion.toString()
                    "ro.build.version.base_os", "ro.boot.container", "ro.vendor.build.fingerprint", "ro.build.expect.bootloader", "ro.build.expect.baseband" -> ""
                    "ro.build.version.security_patch" -> "2023-08-01"
                    "ro.build.version.preview_sdk" -> "0"
                    "ro.build.version.codename", "ro.build.version.all_codenames" -> "REL"
                    "ro.build.type" -> "user"
                    "ro.build.tags" -> "release-keys"
                    "ro.treble.enabled" -> "true"
                    "ro.build.date.utc" -> "1692087179"
                    "ro.build.user" -> "builder"
                    "ro.build.host" -> "pangu-build-component-system-154250-9q7ms-lfm4h-qhs2q"
                    "net.bt.name" -> "Android"
                    "ro.build.characteristics" -> "default"
                    "ro.build.description" -> "mondrian-user 12 TKQ1.220905.001 release-keys"
                    "ro.product.locale" -> "zh-CN"
                    "ro.build.flavor" -> "missi_phoneext4_cn-user"
                    "ro.config.ringtone" -> "Ring_Synth_04.ogg"
                    else -> {
                        logger.warning("Not support prop:$key, return -1")
                        "-1"
                    }
                }
            )
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->getAppVersionName(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(
                vm, when (val key = vaList.getObjectArg<StringObject>(0).value) {
                    "empty" -> this.vm.envData.version
                    else -> {
                        logger.warning("Not support getAppVersionName:$key, return -1")
                        "-1"
                    }
                }
            )
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->getAppVersionCode(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(
                vm, when (val key = vaList.getObjectArg<StringObject>(0).value) {
                    "empty" -> this.vm.envData.code
                    else -> {
                        logger.warning("Not support getAppVersionCode:$key, return -1")
                        "-1"
                    }
                }
            )
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->getAppInstallTime(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(
                vm, File(QSignService.Factory.basePath, "config.json").lastModified().toString()
            )
        }
        if (
            signature == "com/tencent/mobileqq/dt/app/Dtc->getDensity(Ljava/lang/String;)Ljava/lang/String;" ||
            signature == "com/tencent/mobileqq/dt/app/Dtc->getFontDpi(Ljava/lang/String;)Ljava/lang/String;"
        ) {
            return StringObject(vm, CommonConfig.density)
        }
        if ("com/tencent/mobileqq/dt/app/Dtc->getScreenSize(Ljava/lang/String;)Ljava/lang/String;" == signature) {
            return StringObject(vm, "[${CommonConfig.screenSizeWidth},${CommonConfig.screenSizeHeight}]")
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->getStorage(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(vm, storageSize)
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->systemGetSafe(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(
                // System.getProperty(key) ?: "-1"
                vm, when (val key = vaList.getObjectArg<StringObject>(0).value) {
                    "java.io.tmpdir" -> "/data/user/0/${envData.packageName}/cache"
                    "user.home" -> ""
                    "user.locale" -> "zh-CN"
                    "http.agent" -> "Dalvik/2.1.0 (Linux; U; Android ${androidVersion}; 22101317C Build/TKQ1.221013.002)"
                    "java.vm.version" -> "2.1.0"
                    "os.version" -> "3.18.79"
                    "persist.sys.timezone" -> "-1"
                    "java.runtime.version" -> "0.9"
                    "java.boot.class.path" -> "/system/framework/core-oj.jar:/system/framework/core-libart.jar:/system/framework/conscrypt.jar:/system/frameworkhttp.jar:/system/framework/bouncycastle.jar:/system/framework/apache-xml.jar:/system/framework/legacy-test.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/telephony-common.jar:/system/frameworkoip-common.jar:/system/framework/ims-common.jar:/system/framework/org.apache.http.legacy.boot.jar:/system/framework/android.hidl.base-V1.0-java.jar:/system/framework/android.hidl.manager-V1.0-java.jar:/system/framework/mediatek-common.jar:/system/framework/mediatek-framework.jar:/system/framework/mediatek-telephony-common.jar:/system/framework/mediatek-telephony-base.jar:/system/framework/mediatek-ims-common.jar:/system/framework/mediatek-telecom-common.jar:/system/framework/mediatek-cta.jar"
                    else -> {
                        logger.warning("Not support systemGetSafe:$key, return -1")
                        "-1"
                    }
                }
            )
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->getIME(Ljava/lang/String;)Ljava/lang/String;") {
            return StringObject(vm, "com.baidu.input_mi/.ImeService")
        }

        if (signature == "java/lang/Thread->currentThread()Ljava/lang/Thread;") {
            return vm.resolveClass("java/lang/Thread").newObject(null)
        }

        if (signature.startsWith("com/tencent/mobileqq/qsec/qsecest/QsecEst->") && signature.endsWith("(Landroid/content/Context;I)Ljava/lang/String;")) {
            val id = vaList.getIntArg(1)
            return StringObject(vm, when (id) {
                0 -> "$androidSdkVersion"
                1 -> "k1"
                23 -> "8" // CPU数量
                25 -> "0.0.12"
                26 -> "90721e0b3a587f77503b6abedd960c2e".uppercase() // 签名md5
                27 -> "0"  // 是否有xposed
                28 -> envData.packageName
                31 -> "0" // 是否锁屏
                41 -> "" // Hardware
                42 -> "WiFi"
                43 -> envData.packageName
                44 -> getRandomIntString(7) // 剩余内存
                45 -> storageSize // 磁盘大小
                46 -> "0" // 是否有qemu环境
                47 -> "0" // 是否存在qemu文件
                48 -> "0" // 是否处于代理状态
                49 -> "0" // SU
                50 -> getRandomIntString(10)
                51 -> envData.version
                52 -> envData.code
                68 -> "0" // VPN
                70 -> "java.agent"
                80, 71 -> "Asia/Shanghai"
                72 -> "800,1217"
                73 -> androidVersion
                74 -> "100" // screen_brightness
                75 -> Random.nextInt(0 .. 500000).toString()
                76 -> "1,20,50"
                77 -> (1024 * 1024 * 1024 * 32L).toString()
                78 -> "0" // su Bin
                79 -> "1.1.2"
                81 -> "zh"
                82 -> "90721e0b3aaa7f77503b6abedd960c2e".uppercase()
                83 -> "0"
                86 -> fun(): String {
                    val data = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
                    val secureRandom = SecureRandom()
                    val sb = StringBuilder(32)
                    for (i2 in 0 until 32) {
                        sb.append(data[secureRandom.nextInt(data.size)])
                    }
                    return sb.toString()
                }()
                87 -> "0" // busy box
                88 -> "0" // magisk
                89 -> System.currentTimeMillis().toString()
                in 90 .. 105 -> "0"
                else -> {
                    logger.warning("不支持的QSecEstInfo ID: $id, 已返回0")
                    "0"
                }
            })
        }

        if ("com/tencent/secprotocol/t/s->c(Landroid/content/Context;)Ljava/lang/String;" == signature) {
            return StringObject(vm, envData.packageName)
        }

        if ("com/tencent/secprotocol/t/s->d(Landroid/content/Context;)Ljava/lang/String;" == signature) {
            return StringObject(vm, "90721e0b3a587f77503b6abedd960c2e".uppercase())
        }

        return super.callStaticObjectMethodV(vm, dvmClass, signature, vaList)
    }

    override fun callStaticIntMethodV(vm: BaseVM?, dvmClass: DvmClass?, signature: String?, vaList: VaList?): Int {
        if ("com/tencent/secprotocol/t/s->e(Landroid/content/Context;)I" == signature) {
            return when (envData.version) {
                "3.5.1" -> 345546704
                "3.5.2" -> 345971138
                else -> error("不支持该TIM版本")
            }
        }
        return super.callStaticIntMethodV(vm, dvmClass, signature, vaList)
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList) {
        if (signature == "com/tencent/mobileqq/fe/utils/DeepSleepDetector->stopCheck()V") {
            if ("DeepSleepDetector" in global) {
                (global["DeepSleepDetector"] as DeepSleepDetector).stopped = true
            }
            return
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->mmKVSaveValue(Ljava/lang/String;Ljava/lang/String;)V") {
            val key = vaList.getObjectArg<StringObject>(0).value
            val value = vaList.getObjectArg<StringObject>(1).value
            global[key] = value
            return
        }
        if (signature == "com/tencent/mobileqq/dt/app/Dtc->saveList(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V") {
            return
        }
        super.callStaticVoidMethodV(vm, dvmClass, signature, vaList)
    }

    override fun callObjectMethodV(
        vm: BaseVM,
        dvmObject: DvmObject<*>,
        signature: String,
        vaList: VaList
    ): DvmObject<*> {
        if (signature == "android/content/Context->getApplicationInfo()Landroid/content/pm/ApplicationInfo;") {
            return vm.resolveClass("android/content/pm/ApplicationInfo").newObject(null)
        }
        if (signature == "android/content/Context->getFilesDir()Ljava/io/File;") {
            return vm.resolveClass("android/content/Context")
                .also {
                    it.superClass = vm.resolveClass("java/io/File").apply {
                        this.superClass = it
                    }
                }
                .newObject(File("/data/user/0/${envData.packageName}/files"))
            //return vm.resolveClass("java/io/File", vm.resolveClass("android/content/Context"))
            //    .newObject(File("/data/user/0/${envData.packageName}/files"))

            //if (envData.version == "3.5.1") {
            //
            //} else {
            //}
        }
        if (signature == "android/content/Context->getContentResolver()Landroid/content/ContentResolver;") {
            return vm.resolveClass("android/content/ContentResolver")
                .newObject(null)
        }
        if (signature == "android/content/pm/PackageManager->queryIntentServices(Landroid/content/Intent;I)Ljava/util/List;") {
            return vm.resolveClass("java/util/List").newObject(ArrayList<Nothing>())
        }
        if (signature == "android/content/Intent->addCategory(Ljava/lang/String;)Landroid/content/Intent;") {
            return dvmObject
        }
        if (signature == "java/io/File->getPackageResourcePath()Ljava/lang/String;") {
            return StringObject(vm, "${FileResolver.getAppInstallFolder(envData.packageName)}/base.apk")
        }
        if (signature == "android/content/Context->getPackageResourcePath()Ljava/lang/String;") {
            return StringObject(vm, "${FileResolver.getAppInstallFolder(envData.packageName)}/base.apk")
        }
        if (signature == "android/content/Context->getPackageName()Ljava/lang/String;") {
            return StringObject(vm, envData.packageName)
        }
        if (signature == "java/lang/ClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;") {
            val name = vaList.getObjectArg<StringObject>(0).value
            val loader = dvmObject.value as ClassLoader
            try {
                return vm
                    .resolveClass("java/lang/Class")
                    .newObject(loader.loadClass(name))
            } catch (e: ClassNotFoundException) {
                vm.throwException(
                    vm
                        .resolveClass("java.lang.ClassNotFoundException")
                        .newObject(e)
                )
            }
            return vm
                .resolveClass("java/lang/Class")
                .newObject(null)
        }
        if ("java/lang/Thread->getStackTrace()[Ljava/lang/StackTraceElement;" == signature) {
            return ArrayObject()
        }
        if ("com/tencent/mobileqq/qsec/qsecurity/QSec->getEstInfo()Ljava/lang/String;" == signature) {
            val est = global["est_data"] as? com.github.unidbg.linux.android.dvm.array.ByteArray
            return if (est == null || est.value == null) {
                StringObject(vm, "e_null")
            } else {
                val byteArray = est.value
                val b64 = SelfBase64.Encoder.RFC4648.encodeToString(byteArray)
                StringObject(vm, b64)
            }
        }
        if ("android/content/Context->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;" == signature) {
            return vm.resolveClass("java/io/File")
                .newObject(File("/mnt/sdcard"))
        }
        if ("android/content/Context->toString()Ljava/lang/String;" == signature) {
            return StringObject(vm, dvmObject.value.toString())
        }
        return super.callObjectMethodV(vm, dvmObject, signature, vaList)
    }
    override fun acceptMethod(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean {
        if (signature == "com/tencent/mobileqq/qsec/qsecest/QsecEst->p(Landroid/content/Context;I)Ljava/lang/String;"
            && envData.packageName == "com.tencent.mobileqq") {
            return false
        }
        if (signature == "com/tencent/qqprotect/qsec/QSecFramework->goingUp(JJJJLjava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;[Ljava/lang/Object;)I"
            && envData.packageName == "com.tencent.mobileqq") {
            return false
        }
        if (CONFIG.unidbg.debug) {
            println("Accept ${ if (isStatic) "static" else "" } $signature")
        }
        return super.acceptMethod(dvmClass, signature, isStatic)
    }

    override fun toReflectedMethod(vm: BaseVM?, dvmClass: DvmClass?, signature: String?): DvmObject<*> {
        //println("toReflectedMethod")
        return super.toReflectedMethod(vm, dvmClass, signature)
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*> {
        if (signature == "java/io/File-><init>(Ljava/lang/String;)V") {
            val path = vaList.getObjectArg<StringObject>(0).value
            return vm
                .resolveClass("java/io/File")
                .newObject(File(path))
        }
        if (signature == "com/tencent/mobileqq/sign/QQSecuritySign\$SignResult-><init>()V") {
            return QQSecuritySign.SignResultObject(vm)
        }
        if (signature == "android/content/Intent-><init>(Ljava/lang/String;)V") {
            return vm
                .resolveClass("android/content/Intent")
                .newObject(hashMapOf("action" to (vaList.getObjectArg(0) as StringObject).value))
        }
        return super.newObjectV(vm, dvmClass, signature, vaList)
    }

    override fun callBooleanMethodV(
        vm: BaseVM,
        dvmObject: DvmObject<*>,
        signature: String,
        vaList: VaList
    ): Boolean {
        if (signature == "java/io/File->canRead()Z") {
            val file = dvmObject.value as File
            if (
                file.toString() == "\\data\\data\\${envData.packageName}\\.." ||
                file.toString() == "/data/data/${envData.packageName}/.." ||
                file.toString() == "/data/data/" ||
                file.toString() == "/data/data"
            ) {
                return false
            }
        }
        return super.callBooleanMethodV(vm, dvmObject, signature, vaList)
    }

    override fun callObjectMethod(
        vm: BaseVM,
        dvmObject: DvmObject<*>,
        signature: String,
        varArg: VarArg
    ): DvmObject<*> {
        return super.callObjectMethod(vm, dvmObject, signature, varArg)
    }
}
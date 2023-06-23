package xyz.cssxsh.mirai.tool

import kotlinx.serialization.json.*
import net.mamoe.mirai.internal.spi.*
import net.mamoe.mirai.internal.utils.*
import net.mamoe.mirai.utils.*
import java.io.File
import java.util.*

public class TLV544Provider : EncryptService {
    internal companion object {
        val SALT_V1 = arrayOf("810_2", "810_7", "810_24", "810_25")
        val SALT_V2 = arrayOf("810_9", "810_a", "810_d", "810_f")
        val SALT_V3 = arrayOf("812_a")

        @JvmStatic
        internal external fun sign(payload: ByteArray): ByteArray

        init {
            val os = when (val name = System.getProperty("os.name")) {
                "Mac OS X" -> "macos"
                "Linux" -> if (System.getenv("TERMUX_VERSION") != null) "android" else "linux"
                else -> when {
                    name.startsWith("Win") -> "windows"
                    "The Android Project" == System.getProperty("java.specification.vendor") -> "android"
                    else -> throw RuntimeException("Unknown OS $name")
                }
            }
            val arch = when (val name = System.getProperty("os.arch")) {
                "x86" -> "x86"
                "x86_64", "amd64" -> "x64"
                "aarch64" -> "arm64"
                else -> throw RuntimeException("Unknown arch $name")
            }
            val filename = System.mapLibraryName("t544-enc-${os}-${arch}")
            val file = File(System.getProperty("xyz.cssxsh.mirai.tool.t544", filename))
            if (file.isFile.not()) {
                this::class.java.getResource(filename)?.let { resource ->
                    file.writeBytes(resource.readBytes())
                }
            }
            System.load(file.absolutePath)
        }
    }

    private val logger: MiraiLogger = MiraiLogger.Factory.create(this::class)

    @Suppress("INVISIBLE_MEMBER")
    override fun encryptTlv(context: EncryptServiceContext, tlvType: Int, payload: ByteArray): ByteArray? {
        if (tlvType != 0x544) return null
        val command = context.extraArgs[EncryptServiceContext.KEY_COMMAND_STR]

        logger.info("t544 command: $command")

        when (command) {
            in SALT_V2 -> {
                // from MiraiGo
                sign(payload.copyInto(ByteArray(payload.size) { 0 }, 4, 4))
            }
            else -> {
                sign(payload)
            }
        }

        return sign(payload)
    }
}
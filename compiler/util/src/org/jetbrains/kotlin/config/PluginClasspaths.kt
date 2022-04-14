/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

class PluginClasspaths {
    companion object {
        fun serialize(classpaths: Array<String>?) = classpaths?.mapNotNull { it ->
            val jar = File(it).takeIf { it.exists() } ?: return@mapNotNull null
            val jarPath = jar.absolutePath
            val jarHash = jar.sha256()
            "$jarPath-$jarHash"
        }?.joinToString(":") ?: ""

        fun deserialize(str: String): List<String> =
            str.split(":")
                .map { it.substringBeforeLast("-") }
                .filter(String::isNotBlank)

        private fun File.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(this.inputStream(), digest).use { dis ->
                val buffer = ByteArray(8192)
                var bytesRead = 0
                while (bytesRead != -1) {
                    bytesRead = dis.read(buffer, 0, buffer.size)
                }
            }
            // Convert to hex:
            return digest.digest().joinToString("") {
                Integer.toHexString((it.toInt() and 0xff) + 0x100).substring(1)
            }
        }
    }
}
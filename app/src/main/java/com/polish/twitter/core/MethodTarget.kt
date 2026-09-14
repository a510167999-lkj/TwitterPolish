package com.polish.twitter.core

import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 缓存完整 JVM 描述符；反射时同时核对声明类、参数和返回类型。 */
internal object MethodTarget {
    fun descriptor(member: Executable): String =
        "${type(member.declaringClass)}->${if (member is Method) member.name else "<init>"}" +
            member.parameterTypes.joinToString("", "(", ")") { type(it) } +
            if (member is Method) type(member.returnType) else "V"

    private fun type(clazz: Class<*>): String = when (clazz) {
        java.lang.Void.TYPE -> "V"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        else -> if (clazz.isArray) clazz.name.replace('.', '/') else "L${clazz.name.replace('.', '/')};"
    }

    fun resolve(value: String, loader: ClassLoader): Executable? = runCatching {
        val ownerEnd = value.indexOf(";->")
        require(value.startsWith("L") && ownerEnd > 1)
        val owner = Class.forName(value.substring(1, ownerEnd).replace('/', '.'), false, loader)
        val members: List<Executable> = owner.declaredMethods.toList() + owner.declaredConstructors.toList()
        members.singleOrNull { descriptor(it) == value }?.takeUnless {
            Modifier.isAbstract(it.modifiers) || it.isSynthetic || it.declaringClass.isInterface
        }
    }.getOrNull()

    fun <T> unique(candidates: Collection<T>): T? = candidates.distinct().singleOrNull()
}

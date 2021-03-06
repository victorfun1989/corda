package net.corda.core.serialization.amqp

import java.io.NotSerializableException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.util.*

/**
 * Implementation of [ParameterizedType] that we can actually construct, and a parser from the string representation
 * of the JDK implementation which we use as the textual format in the AMQP schema.
 */
class DeserializedParameterizedType(private val rawType: Class<*>, private val params: Array<out Type>, private val ownerType: Type? = null) : ParameterizedType {
    init {
        if (params.isEmpty()) {
            throw NotSerializableException("Must be at least one parameter type in a ParameterizedType")
        }
        if (params.size != rawType.typeParameters.size) {
            throw NotSerializableException("Expected ${rawType.typeParameters.size} for ${rawType.name} but found ${params.size}")
        }
        // We do not check bounds.  Both our use cases (Collection and Map) are not bounded.
        if (rawType.typeParameters.any { boundedType(it) }) throw NotSerializableException("Bounded types in ParameterizedTypes not supported, but found a bound in $rawType")
    }

    private fun boundedType(type: TypeVariable<out Class<out Any>>): Boolean {
        return !(type.bounds.size == 1 && type.bounds[0] == Object::class.java)
    }

    val isFullyWildcarded: Boolean = params.all { it == SerializerFactory.AnyType }

    private val _typeName: String = makeTypeName()

    private fun makeTypeName(): String {
        return if (isFullyWildcarded) {
            rawType.name
        } else {
            val paramsJoined = params.map { it.typeName }.joinToString(", ")
            "${rawType.name}<$paramsJoined>"
        }
    }

    companion object {
        // Maximum depth/nesting of generics before we suspect some DoS attempt.
        const val MAX_DEPTH: Int = 32

        fun make(name: String, cl: ClassLoader = DeserializedParameterizedType::class.java.classLoader): Type {
            val paramTypes = ArrayList<Type>()
            val pos = parseTypeList("$name>", paramTypes, cl)
            if (pos <= name.length) {
                throw NotSerializableException("Malformed string form of ParameterizedType. Unexpected '>' at character position $pos of $name.")
            }
            if (paramTypes.size != 1) {
                throw NotSerializableException("Expected only one type, but got $paramTypes")
            }
            return paramTypes[0]
        }

        private fun parseTypeList(params: String, types: MutableList<Type>, cl: ClassLoader, depth: Int = 0): Int {
            var pos = 0
            var typeStart = 0
            var needAType = true
            var skippingWhitespace = false
            while (pos < params.length) {
                if (params[pos] == '<') {
                    val typeEnd = pos++
                    val paramTypes = ArrayList<Type>()
                    pos = parseTypeParams(params, pos, paramTypes, cl, depth + 1)
                    types += makeParameterizedType(params.substring(typeStart, typeEnd).trim(), paramTypes, cl)
                    typeStart = pos
                    needAType = false
                } else if (params[pos] == ',') {
                    val typeEnd = pos++
                    val typeName = params.substring(typeStart, typeEnd).trim()
                    if (!typeName.isEmpty()) {
                        types += makeType(typeName, cl)
                    } else if (needAType) {
                        throw NotSerializableException("Expected a type, not ','")
                    }
                    typeStart = pos
                    needAType = true
                } else if (params[pos] == '>') {
                    val typeEnd = pos++
                    val typeName = params.substring(typeStart, typeEnd).trim()
                    if (!typeName.isEmpty()) {
                        types += makeType(typeName, cl)
                    } else if (needAType) {
                        throw NotSerializableException("Expected a type, not '>'")
                    }
                    return pos
                } else {
                    // Skip forwards, checking character types
                    if (pos == typeStart) {
                        skippingWhitespace = false
                        if (params[pos].isWhitespace()) {
                            typeStart = pos++
                        } else if (!needAType) {
                            throw NotSerializableException("Not expecting a type")
                        } else if (params[pos] == '*') {
                            pos++
                        } else if (!params[pos].isJavaIdentifierStart()) {
                            throw NotSerializableException("Invalid character at start of type: ${params[pos]}")
                        } else {
                            pos++
                        }
                    } else {
                        if (params[pos].isWhitespace()) {
                            pos++
                            skippingWhitespace = true
                        } else if (!skippingWhitespace && (params[pos] == '.' || params[pos].isJavaIdentifierPart())) {
                            pos++
                        } else {
                            throw NotSerializableException("Invalid character in middle of type: ${params[pos]}")
                        }
                    }
                }
            }
            throw NotSerializableException("Missing close generics '>'")
        }

        private fun makeType(typeName: String, cl: ClassLoader): Type {
            // Not generic
            return if (typeName == "?") SerializerFactory.AnyType else Class.forName(typeName, false, cl)
        }

        private fun makeParameterizedType(rawTypeName: String, args: MutableList<Type>, cl: ClassLoader): Type {
            return DeserializedParameterizedType(makeType(rawTypeName, cl) as Class<*>, args.toTypedArray(), null)
        }

        private fun parseTypeParams(params: String, startPos: Int, paramTypes: MutableList<Type>, cl: ClassLoader, depth: Int): Int {
            if (depth == MAX_DEPTH) {
                throw NotSerializableException("Maximum depth of nested generics reached: $depth")
            }
            return startPos + parseTypeList(params.substring(startPos), paramTypes, cl, depth)
        }
    }

    override fun getRawType(): Type = rawType

    override fun getOwnerType(): Type? = ownerType

    override fun getActualTypeArguments(): Array<out Type> = params

    override fun getTypeName(): String = _typeName

    override fun toString(): String = _typeName

    override fun hashCode(): Int {
        return Arrays.hashCode(this.actualTypeArguments) xor Objects.hashCode(this.ownerType) xor Objects.hashCode(this.rawType)
    }

    override fun equals(other: Any?): Boolean {
        if (other is ParameterizedType) {
            if (this === other) {
                return true
            } else {
                return this.ownerType == other.ownerType && this.rawType == other.rawType && Arrays.equals(this.actualTypeArguments, other.actualTypeArguments)
            }
        } else {
            return false
        }
    }
}
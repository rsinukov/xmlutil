/*
 * Copyright (c) 2018.
 *
 * This file is part of XmlUtil.
 *
 * This file is licenced to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You should have received a copy of the license with the source distribution.
 * Alternatively, you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package nl.adaptivity.xmlutil.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.serialization.impl.XmlQNameSerializer
import nl.adaptivity.xmlutil.serialization.structure.*
import nl.adaptivity.xmlutil.util.ICompactFragment

internal open class XmlEncoderBase internal constructor(
    context: SerializersModule,
    config: XmlConfig,
    val target: XmlWriter
) : XmlCodecBase(context, config) {
    private var nextAutoPrefixNo: Int = 1

    override val namespaceContext: NamespaceContext
        get() = target.namespaceContext

    /**
     * Encoder class for all primitives (except for initial values). It does not handle attributes. This does not
     * implement XmlOutput as
     */
    internal open inner class XmlEncoder(
        xmlDescriptor: XmlDescriptor,
        private val elementIndex: Int,
        private val discriminatorName: QName? = null
    ) :
        XmlCodec<XmlDescriptor>(xmlDescriptor), Encoder, XML.XmlOutput {

        override val target: XmlWriter get() = this@XmlEncoderBase.target

        override val serializersModule get() = this@XmlEncoderBase.serializersModule
        override val config: XmlConfig get() = this@XmlEncoderBase.config

        override fun ensureNamespace(qName: QName): QName {
            return this@XmlEncoderBase.ensureNamespace(qName)
        }

        override fun encodeBoolean(value: Boolean) =
            encodeString(value.toString())

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun encodeByte(value: Byte) =
            when (xmlDescriptor.isUnsigned) {
                true -> encodeString(value.toUByte().toString())
                else -> encodeString(value.toString())
            }

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun encodeShort(value: Short) =
            when (xmlDescriptor.isUnsigned) {
                true -> encodeString(value.toUShort().toString())
                else -> encodeString(value.toString())
            }

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun encodeInt(value: Int) =
            when (xmlDescriptor.isUnsigned) {
                true -> encodeString(value.toUInt().toString())
                else -> encodeString(value.toString())
            }

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun encodeLong(value: Long) =
            when (xmlDescriptor.isUnsigned) {
                true -> encodeString(value.toULong().toString())
                else -> encodeString(value.toString())
            }

        override fun encodeFloat(value: Float) =
            encodeString(value.toString())

        override fun encodeDouble(value: Double) =
            encodeString(value.toString())

        override fun encodeChar(value: Char) =
            encodeString(value.toString())

        private fun encodeQName(value: QName) {
            val effectiveQName: QName = ensureNamespace(value)

            XmlQNameSerializer.serialize(this, effectiveQName)
        }

        override fun encodeString(value: String) {
            // string doesn't need parsing so take a shortcut
            val defaultValue =
                (xmlDescriptor as XmlValueDescriptor).default

            if (value == defaultValue) return

            when (xmlDescriptor.outputKind) {
                OutputKind.Inline, // shouldn't occur, but treat as element
                OutputKind.Element -> { // This may occur with list values.
                    target.smartStartTag(serialName) {
                        if (discriminatorName != null) {
                            val typeRef = ensureNamespace(config.policy.typeQName(xmlDescriptor))
                            smartWriteAttribute(discriminatorName, typeRef.toPrefixed(), serialName.namespaceURI)
                        }
                        if (xmlDescriptor.isCData) target.cdsect(value) else target.text(value)
                    }
                }
                OutputKind.Attribute -> {
                    smartWriteAttribute(serialName, value, xmlDescriptor.tagParent.namespace.namespaceURI)
                }
                OutputKind.Mixed,
                OutputKind.Text -> {
                    if (xmlDescriptor.isCData) target.cdsect(value) else target.text(value)
                }
            }
        }

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            val stringRepr = config.policy.enumEncoding(enumDescriptor, index)
            encodeString(stringRepr)
        }

        @ExperimentalSerializationApi
        override fun encodeNotNullMark() {
            // Not null is presence, no mark needed
        }

        @ExperimentalSerializationApi
        override fun encodeNull() {
            val nilAttr = config.nilAttribute
            if (xmlDescriptor.outputKind == OutputKind.Element && nilAttr != null) {
                target.smartStartTag(serialName) {
                    if (discriminatorName != null) {
                        val typeRef = ensureNamespace(config.policy.typeQName(xmlDescriptor))
                        smartWriteAttribute(discriminatorName, typeRef.toPrefixed(), serialName.namespaceURI)
                    }
                    smartWriteAttribute(nilAttr.first, nilAttr.second, serialName.namespaceURI)
                }
            }
            // Null is absence, no mark needed, except in nil mode
        }

        override fun <T> encodeSerializableValue(
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val effectiveSerializer = xmlDescriptor.effectiveSerializationStrategy(serializer)

            when (effectiveSerializer) {
                XmlQNameSerializer -> encodeQName(value as QName)
                else -> effectiveSerializer.serialize(this, value)
            }
        }

        @ExperimentalSerializationApi
        override fun encodeInline(inlineDescriptor: SerialDescriptor): Encoder {
            return XmlEncoder(xmlDescriptor.getElementDescriptor(0), elementIndex, discriminatorName)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            return beginEncodeCompositeImpl(xmlDescriptor, elementIndex, discriminatorName)
        }
    }

    internal inner class NSAttrXmlEncoder(
        xmlDescriptor: XmlDescriptor,
        namespaces: Iterable<Namespace>,
        elementIndex: Int
    ) :
        XmlEncoder(xmlDescriptor, elementIndex) {

        private val namespaces = namespaces.toList()

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            val compositeEncoder = super.beginStructure(descriptor)
            for (namespace in namespaces) {
                if (target.getNamespaceUri(namespace.prefix) == null) {
                    target.namespaceAttr(namespace)
                }
            }
            return compositeEncoder
        }
    }

    internal inner class PrimitiveEncoder(
        override val serializersModule: SerializersModule,
        val xmlDescriptor: XmlDescriptor,
    ) : Encoder, XML.XmlOutput {
        val output = StringBuilder()
        override val config: XmlConfig get() = this@XmlEncoderBase.config
        override val serialName: QName get() = xmlDescriptor.tagName
        override val target: XmlWriter get() = this@XmlEncoderBase.target

        override fun ensureNamespace(qName: QName): QName {
            return this@XmlEncoderBase.ensureNamespace(qName)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            throw IllegalArgumentException("Primitives cannot be structs")
        }

        override fun encodeBoolean(value: Boolean) = encodeString(value.toString())

        override fun encodeByte(value: Byte) = encodeString(value.toString())

        override fun encodeChar(value: Char) = encodeString(value.toString())

        override fun encodeDouble(value: Double) = encodeString(value.toString())

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            val tagName = xmlDescriptor.getElementDescriptor(index).tagName
            if (tagName.namespaceURI == "" && tagName.prefix == "") {
                encodeString(tagName.localPart)
            } else {
                encodeSerializableValue(QNameSerializer, tagName)
            }
        }

        override fun encodeFloat(value: Float) = encodeString(value.toString())

        @ExperimentalSerializationApi
        override fun encodeInline(inlineDescriptor: SerialDescriptor): Encoder = this

        override fun encodeInt(value: Int) = encodeString(value.toString())

        override fun encodeLong(value: Long) = encodeString(value.toString())

        @ExperimentalSerializationApi
        override fun encodeNull() {}

        override fun encodeShort(value: Short) = encodeString(value.toString())

        override fun encodeString(value: String) {
            output.append(value)
        }

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            val effectiveSerializer = xmlDescriptor.effectiveSerializationStrategy(serializer)
            when (effectiveSerializer) {
                XmlQNameSerializer -> XmlQNameSerializer.serialize(this, ensureNamespace(value as QName))
                else -> super.encodeSerializableValue(serializer, value)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    internal fun beginEncodeCompositeImpl(
        xmlDescriptor: XmlDescriptor,
        elementIndex: Int,
        discriminatorName: QName?
    ): CompositeEncoder {

        return when (xmlDescriptor.serialKind) {
            is PrimitiveKind -> throw AssertionError("A primitive is not a composite")

            SerialKind.CONTEXTUAL, // TODO handle contextual in a more elegant way
            StructureKind.MAP -> when {
                xmlDescriptor.outputKind == OutputKind.Attribute -> {
                    val valueType = xmlDescriptor.getElementDescriptor(1)
                    if (!valueType.effectiveOutputKind.isTextual &&
                        valueType.overriddenSerializer != XmlQNameSerializer
                    ) {
                        throw XmlSerialException("Values of an attribute map must be textual or a qname")
                    }
                    val keyType = xmlDescriptor.getElementDescriptor(0)
                    if (keyType.overriddenSerializer != XmlQNameSerializer &&
                        !keyType.effectiveOutputKind.isTextual
                    ) {
                        throw XmlSerialException("The keys of an attribute map must be string or qname")
                    }
                    AttributeMapEncoder(xmlDescriptor)
                }
                else -> TagEncoder(xmlDescriptor, discriminatorName)
            }
            StructureKind.CLASS,
            StructureKind.OBJECT,
            SerialKind.ENUM -> TagEncoder(xmlDescriptor, discriminatorName)

            StructureKind.LIST -> when {
                xmlDescriptor.outputKind == OutputKind.Attribute ->
                    AttributeListEncoder(xmlDescriptor as XmlListDescriptor, elementIndex)

                else -> ListEncoder(xmlDescriptor as XmlListDescriptor, elementIndex, discriminatorName)
            }

            is PolymorphicKind -> PolymorphicEncoder(xmlDescriptor as XmlPolymorphicDescriptor)
        }.apply { writeBegin() }
    }

    private inner class InlineEncoder<D : XmlDescriptor>(
        private val parent: TagEncoder<D>,
        private val childIndex: Int,
        discriminatorName: QName? = null
    ) : XmlEncoder(parent.xmlDescriptor.getElementDescriptor(childIndex), childIndex, discriminatorName) {
        override fun encodeString(value: String) {
            val d = xmlDescriptor.getElementDescriptor(0)
            parent.encodeStringElement(d, childIndex, value)
        }

        override fun <T> encodeSerializableValue(
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val d = xmlDescriptor.getElementDescriptor(0)
            parent.encodeSerializableElement(
                d,
                childIndex,
                serializer,
                value
            )
        }

        @ExperimentalSerializationApi
        override fun encodeInline(inlineDescriptor: SerialDescriptor): Encoder {
            return this
        }
    }

    internal open inner class TagEncoder<D : XmlDescriptor>(
        xmlDescriptor: D,
        protected val discriminatorName: QName?,
        private var deferring: Boolean = true
    ) : XmlTagCodec<D>(xmlDescriptor), CompositeEncoder, XML.XmlOutput {

        override val target: XmlWriter get() = this@XmlEncoderBase.target
        override val namespaceContext: NamespaceContext get() = this@XmlEncoderBase.target.namespaceContext

        override fun ensureNamespace(qName: QName): QName {
            return this@XmlEncoderBase.ensureNamespace(qName)
        }

        private val deferredBuffer =
            mutableListOf<Pair<Int, CompositeEncoder.() -> Unit>>()

        private val reorderInfo =
            (xmlDescriptor as? XmlCompositeDescriptor)?.childReorderMap

        open fun writeBegin() {
            target.smartStartTag(serialName)
            if (discriminatorName != null) {
                val typeName = ensureNamespace(config.policy.typeQName(xmlDescriptor))
                smartWriteAttribute(discriminatorName, typeName.toPrefixed(), serialName.namespaceURI)
            }
        }

        open fun defer(index: Int, deferred: CompositeEncoder.() -> Unit) {
            if (reorderInfo != null) {
                deferredBuffer.add(reorderInfo[index] to deferred)
            } else if (!deferring) {
                deferred()
            } else {
                val outputKind =
                    xmlDescriptor.getElementDescriptor(index).outputKind
                if (outputKind == OutputKind.Attribute) {
                    deferred()
                } else {
                    deferredBuffer.add(index to deferred)
                }
            }
        }

        final override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            encodeSerializableElement(xmlDescriptor.getElementDescriptor(index), index, serializer, value)
        }

        @OptIn(ExperimentalSerializationApi::class)
        internal open fun <T> encodeSerializableElement(
            elementDescriptor: XmlDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val encoder = when {
                elementDescriptor.doInline -> InlineEncoder(this, index)
                else -> XmlEncoder(elementDescriptor, index)
            }

            val effectiveSerializer = xmlDescriptor.getElementDescriptor(index).effectiveSerializationStrategy(serializer)

            when (effectiveSerializer) {
                XmlQNameSerializer -> encodeQName(elementDescriptor, index, value as QName)
                CompactFragmentSerializer -> if (xmlDescriptor.getValueChild() == index) {
                    defer(index) {
                        CompactFragmentSerializer.writeCompactFragmentContent(this, value as ICompactFragment)
                    }
                } else {
                    defer(index) { effectiveSerializer.serialize(encoder, value) }
                }
                else -> defer(index) { effectiveSerializer.serialize(encoder, value) }
            }
        }

        @ExperimentalSerializationApi
        override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
            return InlineEncoder(this, index, null)
        }

        @ExperimentalSerializationApi
        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean {
            val elementDescriptor = xmlDescriptor.getElementDescriptor(index)

            return config.policy.shouldEncodeElementDefault(elementDescriptor)
        }

        final override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
            encodeStringElement(descriptor, index, value.toString())
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        final override fun encodeByteElement(
            descriptor: SerialDescriptor,
            index: Int,
            value: Byte
        ) {
            when (xmlDescriptor.isUnsigned) {
                true -> encodeStringElement(descriptor, index, value.toUByte().toString())
                else -> encodeStringElement(descriptor, index, value.toString())
            }
        }


        @OptIn(ExperimentalUnsignedTypes::class)
        final override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
            when (xmlDescriptor.isUnsigned) {
                true -> encodeStringElement(descriptor, index, value.toUShort().toString())
                else -> encodeStringElement(
                    descriptor, index, value.toString()
                )
            }
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        final override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
            when (xmlDescriptor.isUnsigned) {
                true -> encodeStringElement(descriptor, index, value.toUInt().toString())
                else -> encodeStringElement(descriptor, index, value.toString())
            }
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        final override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
            when (xmlDescriptor.isUnsigned) {
                true -> encodeStringElement(descriptor, index, value.toULong().toString())
                else -> encodeStringElement(descriptor, index, value.toString())
            }
        }

        final override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
            encodeStringElement(descriptor, index, value.toString())
        }

        final override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
            encodeStringElement(descriptor, index, value.toString())
        }

        final override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
            encodeStringElement(descriptor, index, value.toString())
        }

        @ExperimentalSerializationApi
        override fun <T : Any> encodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T?
        ) {
            val nilAttr = config.nilAttribute
            val elemDescriptor = xmlDescriptor.getElementDescriptor(index)
            if (value != null) {
                encodeSerializableElement(descriptor, index, serializer, value)
            } else if (serializer.descriptor.isNullable) {
                val encoder = when {
                    elemDescriptor.doInline -> InlineEncoder(this, index)
                    else -> XmlEncoder(elemDescriptor, index)
                }

                // This should be safe as we are handling the case when a serializer explicitly handles nulls
                // In such case cast it to accept a null parameter and serialize through the serializer, not
                // indirectly.
                @Suppress("UNCHECKED_CAST")
                defer(index) {
                    (serializer as SerializationStrategy<T?>).serialize(encoder, null)
                }
            } else if (nilAttr!=null && elemDescriptor.effectiveOutputKind == OutputKind.Element) {
                target.smartStartTag(elemDescriptor.tagName) {
                    smartWriteAttribute(nilAttr.first, nilAttr.second, elemDescriptor.tagName.namespaceURI)
                }
            }

            // Null is the absense of values, no need to do more
        }

        private fun encodeQName(elementDescriptor: XmlDescriptor, index: Int, value: QName) {
            val effectiveQName: QName = ensureNamespace(value)

            val encoder = XmlEncoder(elementDescriptor, index)
            defer(index) { XmlQNameSerializer.serialize(encoder, effectiveQName) }
        }

        final override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
            val elementDescriptor = xmlDescriptor.getElementDescriptor(index)

            encodeStringElement(elementDescriptor, index, value)
        }

        internal open fun encodeStringElement(elementDescriptor: XmlDescriptor, index: Int, value: String) {
            val defaultValue = (elementDescriptor as? XmlValueDescriptor)?.default

            if (value == defaultValue) return


            when (elementDescriptor.outputKind) {
                OutputKind.Inline, // Treat inline as if it was element if it occurs (shouldn't happen)
                OutputKind.Element -> defer(index) {
                    target.smartStartTag(elementDescriptor.tagName) {
                        if (elementDescriptor.isCData) target.cdsect(value) else target.text(value)
                    }
                }
                OutputKind.Attribute -> doWriteAttribute(index, elementDescriptor.tagName, value)
                OutputKind.Mixed,
                OutputKind.Text -> defer(index) {
                    if (elementDescriptor.isCData) target.cdsect(value) else target.text(value)
                }
            }
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            deferring = false
            for ((_, deferred) in deferredBuffer.sortedBy { it.first }) {
                deferred()
            }
            target.endTag(serialName)
        }

        open fun doWriteAttribute(index: Int, name: QName, value: String) {

            val actualAttrName: QName = when {
                name.getNamespaceURI().isEmpty() ||
                        (serialName.getNamespaceURI() == name.getNamespaceURI() &&
                                (serialName.prefix == name.prefix))
                -> QName(name.localPart) // Breaks in android otherwise

                else -> name
            }

            if (reorderInfo != null) {
                val deferred: CompositeEncoder.() -> Unit =
                    { smartWriteAttribute(actualAttrName, value, xmlDescriptor.tagName.namespaceURI) }
                deferredBuffer.add(reorderInfo[index] to deferred)
            } else {
                smartWriteAttribute(actualAttrName, value, xmlDescriptor.tagName.namespaceURI)
            }
        }


    }


    private fun NamespaceContext.nextAutoPrefix(): String {
        var prefix: String
        do {
            prefix = "n$nextAutoPrefixNo"
        } while (getNamespaceURI(prefix) != null)
        return prefix
    }

    /**
     * Determine/reserve a a namespace for this element.
     * Will reuse a prefix if available.
     *
     * @param tagNamespace For attributes only, this determines the namespace of the containing tag
     */
    private fun ensureNamespace(qName: QName, tagNamespace: String? = null): QName {
        val registeredNamespace = target.getNamespaceUri(qName.getPrefix())
        val registeredPrefix = target.getPrefix(qName.namespaceURI)
        return when { // Attributes with empty prefix are always in the default namespace, so if they are to be otherwise
            !tagNamespace.isNullOrEmpty() &&
                    tagNamespace != qName.namespaceURI &&
                    qName.prefix == "" &&
                    qName.namespaceURI.isNotEmpty() -> {
                val effectivePrefix = target.namespaceContext.prefixesFor(qName.namespaceURI).asSequence()
                    .firstOrNull { it.isNotEmpty() }
                    ?: namespaceContext.nextAutoPrefix()
                qName.copy(prefix = effectivePrefix)
            }

            // If things match, or no namespace, no need to do anything
            registeredNamespace == qName.namespaceURI ||
                    (qName.namespaceURI == "" && qName.prefix == "")
            -> qName

            // for empty namespace uri, force empty prefix
            qName.namespaceURI == "" -> qName.copy(prefix = "")

            // There is a prefix to reuse, just reuse that (TODO make configurable)
            registeredPrefix != null -> return qName.copy(prefix = registeredPrefix)

            // If there is a namespace for this prefix and it doesn't match, create a new prefix
            registeredNamespace != null -> { // prefix no longer valid
                val prefix = qName.prefix
                var lastDigitIdx = prefix.length
                while (lastDigitIdx > 0 && prefix[lastDigitIdx - 1].isDigit()) {
                    lastDigitIdx -= 1
                }
                val prefixBase: String
                val prefixStart: Int
                when {
                    lastDigitIdx == 0 -> {
                        prefixBase = "ns"
                        prefixStart = 0
                    }
                    lastDigitIdx < prefix.length -> {
                        prefixBase = prefix.substring(0, lastDigitIdx)
                        prefixStart = prefix.substring(lastDigitIdx).toInt()
                    }
                    else -> {
                        prefixBase = prefix
                        prefixStart = 0
                    }
                }
                val newPrefix = (prefixStart..Int.MAX_VALUE)
                    .asSequence()
                    .map { "${prefixBase}$it" }
                    .first { target.getNamespaceUri(it) == null }

                target.namespaceAttr(newPrefix, qName.namespaceURI)
                return qName.copy(prefix = newPrefix)
            }

            else -> { // No existing namespace or prefix
                target.namespaceAttr(qName.prefix, qName.namespaceURI)
                return qName
            }
        }
    }

    /**
     * Helper function that ensures writing the namespace attribute if needed.
     */
    private fun smartWriteAttribute(name: QName, value: String, tagNamespace: String?) {
        val effectiveQName = ensureNamespace(name, tagNamespace)
        if (effectiveQName.prefix != "" && target.getNamespaceUri(effectiveQName.prefix) == null) {
            target.namespaceAttr(effectiveQName.toNamespace())
        }

        target.writeAttribute(effectiveQName, value)
    }

    internal inner class PolymorphicEncoder(xmlDescriptor: XmlPolymorphicDescriptor) :
        TagEncoder<XmlPolymorphicDescriptor>(
            xmlDescriptor,
            discriminatorName = null,
            deferring = false
        ), XML.XmlOutput {

        override fun defer(
            index: Int,
            deferred: CompositeEncoder.() -> Unit
        ) {
            deferred()
        }

        override fun writeBegin() {
            // write the if we're in tag mode
            if (xmlDescriptor.polymorphicMode == PolymorphicMode.TAG) super.writeBegin()
        }

        override fun encodeStringElement(elementDescriptor: XmlDescriptor, index: Int, value: String) {
            val isMixed = xmlDescriptor.outputKind == OutputKind.Mixed
            val polymorphicMode = xmlDescriptor.polymorphicMode
            when {
                index == 0 -> {
                    if (polymorphicMode == PolymorphicMode.TAG) { // Don't write for non-tag mode
                        val childDesc =
                            xmlDescriptor.getElementDescriptor(0)

                        when (childDesc.outputKind) {
                            OutputKind.Attribute -> doWriteAttribute(
                                0,
                                childDesc.tagName,
                                value.tryShortenTypeName(xmlDescriptor.parentSerialName)
                            )

                            OutputKind.Mixed,
                            OutputKind.Inline,
                            OutputKind.Element -> target.smartStartTag(
                                childDesc.tagName
                            ) {
                                text(
                                    value
                                )
                            }

                            OutputKind.Text ->
                                throw XmlSerialException("the type for a polymorphic child cannot be a text")
                        }
                    } // else if (index == 0) { } // do nothing
                }
                polymorphicMode == PolymorphicMode.TRANSPARENT -> {
                    when {
                        isMixed -> target.text(value)
                        else -> target.smartStartTag(serialName) {
                            text(
                                value
                            )
                        }
                    }
                }
                polymorphicMode is PolymorphicMode.ATTR -> {
                    target.smartStartTag(serialName) {
                        val elementQName = ensureNamespace(config.policy.typeQName(elementDescriptor))
                        smartWriteAttribute(polymorphicMode.name, elementQName.toPrefixed(), serialName.namespaceURI)

                        text(value)
                    }
                }
                else -> super.encodeStringElement(
                    elementDescriptor,
                    index,
                    value
                )
            }
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun <T> encodeSerializableElement(
            elementDescriptor: XmlDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val childXmlDescriptor =
                xmlDescriptor.getPolymorphicDescriptor(serializer.descriptor.serialName)

            val discriminatorName = (xmlDescriptor.polymorphicMode as? PolymorphicMode.ATTR)?.name
            val encoder = XmlEncoder(childXmlDescriptor, index, discriminatorName)
            serializer.serialize(encoder, value)
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            // Only write the tag here if we're not in attribute or transparent mode
            if (xmlDescriptor.polymorphicMode == PolymorphicMode.TAG) {
                super.endStructure(descriptor)
            }
        }

    }

    internal inner class AttributeMapEncoder(xmlDescriptor: XmlDescriptor) :
        TagEncoder<XmlDescriptor>(xmlDescriptor, null) {

        lateinit var entryKey: QName
        override fun defer(index: Int, deferred: CompositeEncoder.() -> Unit) {
            deferred()
        }

        override fun encodeStringElement(elementDescriptor: XmlDescriptor, index: Int, value: String) {
            when (index % 2) {
                0 -> entryKey = QName(value)
                1 -> smartWriteAttribute(entryKey, value, null)
            }
        }

        override fun <T> encodeSerializableElement(
            elementDescriptor: XmlDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            if (index % 2 == 0) {
                val effectiveSerializer = elementDescriptor.effectiveSerializationStrategy(serializer)

                entryKey = when (effectiveSerializer) {
                    XmlQNameSerializer -> value as QName
                    else -> QName(PrimitiveEncoder(serializersModule, xmlDescriptor).apply {
                        encodeSerializableValue(effectiveSerializer, value)
                    }.output.toString())
                }
            } else {
                val effectiveSerializer = xmlDescriptor.getElementDescriptor(1).effectiveSerializationStrategy(serializer)

                val entryValue = PrimitiveEncoder(serializersModule, xmlDescriptor).apply {
                    encodeSerializableValue(effectiveSerializer, value)
                }.output.toString()
                doWriteAttribute(index, entryKey, entryValue)
            }
        }

        override fun writeBegin() {}

        override fun endStructure(descriptor: SerialDescriptor) {}
    }

    internal inner class AttributeListEncoder(xmlDescriptor: XmlListDescriptor, private val elementIndex: Int) :
        TagEncoder<XmlListDescriptor>(xmlDescriptor, null) {
        private val valueBuilder = StringBuilder()

        init {
            var d: XmlDescriptor = xmlDescriptor
            var ok: OutputKind
            do {
                d = d.getElementDescriptor(0)
                ok = d.outputKind
            } while (ok == OutputKind.Inline)

            if (ok != OutputKind.Attribute && ok != OutputKind.Text) {
                throw IllegalArgumentException("An xml list stored in an attribute must store atomics, not structs")
            }
        }

        override fun defer(index: Int, deferred: CompositeEncoder.() -> Unit) = deferred()

        override fun writeBegin() {}

        override fun <T> encodeSerializableElement(
            elementDescriptor: XmlDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val encoder = PrimitiveEncoder(serializersModule, elementDescriptor)
            encoder.encodeSerializableValue(serializer, value)
            encodeStringElement(elementDescriptor, index, encoder.output.toString())
        }

        override fun encodeStringElement(elementDescriptor: XmlDescriptor, index: Int, value: String) {
            if (valueBuilder.isNotEmpty()) valueBuilder.append(' ')

            valueBuilder.append(value)
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            doWriteAttribute(elementIndex, xmlDescriptor.tagName, valueBuilder.toString())
        }
    }

    /**
     * Writer that does not actually write an outer tag unless a [XmlChildrenName] is specified. In XML children don't need to
     * be wrapped inside a list (unless it is the root). If [XmlChildrenName] is not specified it will determine tag names
     * as if the list was not present and there was a single value.
     */
    internal inner class ListEncoder(
        xmlDescriptor: XmlListDescriptor,
        private val listChildIdx: Int,
        discriminatorName: QName?
    ) : TagEncoder<XmlListDescriptor>(xmlDescriptor, discriminatorName, deferring = false), XML.XmlOutput {

        private val parentXmlDescriptor: XmlDescriptor get() = xmlDescriptor.tagParent.descriptor as XmlDescriptor

        override fun defer(
            index: Int,
            deferred: CompositeEncoder.() -> Unit
        ) {
            deferred()
        }

        override fun writeBegin() {
            if (!xmlDescriptor.isListEluded) { // Do the default thing if the children name has been specified
                val childName =
                    xmlDescriptor.getElementDescriptor(0).tagName
                super.writeBegin()
                val tagName = serialName

                //declare namespaces on the parent rather than the children of the list.
                if (tagName.prefix != childName.prefix &&
                    target.getNamespaceUri(childName.prefix) != childName.namespaceURI
                ) {
                    target.namespaceAttr(
                        childName.prefix,
                        childName.namespaceURI
                    )
                }

            }

        }

        override fun <T> encodeSerializableElement(
            elementDescriptor: XmlDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val childDescriptor = xmlDescriptor.getElementDescriptor(0)

            @Suppress("UNCHECKED_CAST")
            val effectiveSerializer: SerializationStrategy<T> = elementDescriptor.effectiveSerializationStrategy(serializer)

            when (effectiveSerializer) {
                CompactFragmentSerializer -> if (parentXmlDescriptor.getValueChild() == listChildIdx) {
                    CompactFragmentSerializer.writeCompactFragmentContent(this, value as ICompactFragment)
                } else {
                    serializer.serialize(XmlEncoder(childDescriptor, index), value)
                }
                else -> serializer.serialize(XmlEncoder(childDescriptor, index), value)
            }
        }

        override fun encodeStringElement(elementDescriptor: XmlDescriptor, index: Int, value: String) {
            if (index > 0) {
                XmlEncoder(elementDescriptor, index).encodeString(value)
            }
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            if (!xmlDescriptor.isListEluded) {
                super.endStructure(descriptor)
            }
        }
    }
}

package fi.evident.apina.output.ts

import fi.evident.apina.model.*
import fi.evident.apina.model.parameters.EndpointParameter
import fi.evident.apina.model.parameters.EndpointPathVariableParameter
import fi.evident.apina.model.parameters.EndpointRequestParamParameter
import fi.evident.apina.model.settings.EnumMode
import fi.evident.apina.model.settings.TranslationSettings
import fi.evident.apina.model.settings.TypeWriteMode
import fi.evident.apina.model.type.ApiType
import fi.evident.apina.model.type.ApiTypeName
import fi.evident.apina.output.common.RawCode
import fi.evident.apina.utils.readResourceAsString
import java.lang.String.format

abstract class AbstractTypeScriptGenerator(
    val api: ApiDefinition,
    val settings: TranslationSettings,
    private val resultFunctor: String,
    private val classDecorator: String
) {

    internal val out = TypeScriptWriter()

    val output: String
        get() = out.output

    fun writeApi() {
        writeHeader()
        writeImports()
        writePlatformSpecificImports()
        writeTypes()
        writeCommonRuntime()
        writeRuntime()
        writeEndpoints(api.endpointGroups)
        writePlatformSpecific()
    }

    private fun writeHeader() {
        // The project using our generated code might generate the code to directory where
        // it's checked by TSLint and we don't know what settings are used so just disable
        // TSLint for the whole file.
        out.writeLine("/* tslint:disable */")
    }

    private fun writeImports() {
        val imports = settings.imports

        if (!imports.isEmpty()) {
            for (anImport in imports)
                out.writeImport(anImport.moduleName, anImport.types.map { it.name })

            out.writeLine()
        }
    }

    protected open fun writePlatformSpecificImports() {}

    private fun writeTypes() {

        out.writeExportedInterface("Dictionary<V>") { out.writeLine("[key: string]: V;") }

        for (type in api.allBlackBoxClasses)
            out.writeLine("export type ${type.name} = {};")

        for ((alias, target) in api.typeAliases)
            out.writeLine("export type ${alias.name} = ${target.toTypeScript()};")

        out.writeLine()

        for (enumDefinition in api.enumDefinitions)
            writeEnum(enumDefinition)

        out.writeLine()

        val classDefinitionWriter = when (settings.typeWriteMode) {
            TypeWriteMode.CLASS -> out::writeExportedClass
            TypeWriteMode.INTERFACE -> out::writeExportedInterface
        }

        for (classDefinition in api.classDefinitions) {
            classDefinitionWriter(classDefinition.type.name) {
                for (property in classDefinition.properties)
                    out.writeLine("${property.name}: ${property.type.toTypeScript()};")
            }
        }

        for (definition in api.discriminatedUnionDefinitions)
            writeDiscriminatedUnion(definition)

        writeSerializerDefinitions()
    }

    private fun writeEnum(enumDefinition: EnumDefinition) {
        val constants = enumDefinition.constants
        out.writeLine(when (settings.enumMode) {
            EnumMode.DEFAULT ->
                format("export enum %s { %s }", enumDefinition.type, constants.joinToString(", ") { "$it = \"$it\"" })
            EnumMode.STRING_UNION ->
                format("export type %s = %s;", enumDefinition.type, constants.joinToString(" | ") { "\"$it\"" })
            EnumMode.INT_ENUM ->
                format("export enum %s { %s }", enumDefinition.type, constants.joinToString(", "))
        })
    }

    private fun writeDiscriminatedUnion(definition: DiscriminatedUnionDefinition) {
        // First individual members of the union...
        for ((discriminatorValue, type) in definition.types) {
            val typeName = discriminatedUnionMemberType(definition.type, type)
            out.writeBlock("export interface $typeName extends ${type.toTypeScript()}") {
                out.writeLine("${definition.discriminator}: '$discriminatorValue';")
            }
            out.writeLine()
        }

        // ...then the union itself
        val members = definition.types.values.map { discriminatedUnionMemberType(definition.type, it) }
        out.writeLine("export type ${definition.type.name} = ${members.joinToString(" | ")};")
        out.writeLine()
    }

    private fun writeSerializerDefinitions() {
        fun writeEnumSerializer(enumDefinition: EnumDefinition) {
            val enumName = enumDefinition.type.toString()
            when (settings.enumMode) {
                EnumMode.INT_ENUM ->
                    out.write("config.registerEnumSerializer(").writeValue(enumName).write(", ").write(enumName).writeLine(");")
                EnumMode.DEFAULT, EnumMode.STRING_UNION ->
                    out.write("config.registerIdentitySerializer(").writeValue(enumName).writeLine(");")
            }
        }

        out.write("export function registerDefaultSerializers(config: ApinaConfig) ").writeBlock {
            for (aliasedType in api.typeAliases.keys)
                out.write("config.registerIdentitySerializer(").writeValue(aliasedType.name).writeLine(");")
            for (unknownType in api.allBlackBoxClasses)
                out.write("config.registerIdentitySerializer(").writeValue(unknownType.name).writeLine(");")
            out.writeLine()

            for (enumDefinition in api.enumDefinitions) {
                writeEnumSerializer(enumDefinition)
            }
            out.writeLine()

            for (classDefinition in api.classDefinitions) {
                val defs = LinkedHashMap<String, String>()

                for (property in classDefinition.properties)
                    defs[property.name] = typeDescriptor(property.type)

                out.write("config.registerClassSerializer(").writeValue(classDefinition.type.toString()).write(", ")
                out.writeValue(defs).writeLine(");")
                out.writeLine()
            }

            for (definition in api.discriminatedUnionDefinitions) {
                val defs = mutableMapOf<String, String>()

                for ((discriminatorValue, type) in definition.types)
                    defs[discriminatorValue] = typeDescriptor(type)

                out.write("config.registerDiscriminatedUnionSerializer(")
                out.writeValue(definition.type.name).write(", ")
                out.writeValue(definition.discriminator).write(", ")
                out.writeValue(defs).writeLine(");")
                out.writeLine()
            }
        }

        out.writeLine().writeLine()
    }

    private fun writeCommonRuntime() {
        out.write(readResourceAsString("typescript/runtime-common.ts"))
        out.writeLine()
    }

    protected abstract fun writeRuntime()

    private fun writeEndpoints(endpointGroups: Collection<EndpointGroup>) {
        for (endpointGroup in endpointGroups) {
            if (classDecorator.isNotBlank()) {
                out.writeLine(classDecorator)
            }
            out.writeBlock("export class " + endpointClassName(endpointGroup)) {

                out.writeBlock("constructor(private context: ApinaEndpointContext)") { }

                for (endpoint in endpointGroup.endpoints) {
                    writeEndpoint(endpoint)
                    out.writeLine().writeLine()
                    if (settings.generateUrls) {
                        writeEndpointUrl(endpoint)
                        out.writeLine().writeLine()
                    }
                }
            }
        }
    }

    protected fun endpointClassName(endpointGroup: EndpointGroup): String =
        endpointGroup.name + "Endpoint"

    private fun writeEndpoint(endpoint: Endpoint) {
        out.write(endpointSignature(endpoint)).write(" ").writeBlock { out.write("return this.context.request(").writeValue(
            createConfig(endpoint)
        ).writeLine(");") }
    }

    private fun writeEndpointUrl(endpoint: Endpoint) {
        val config= createConfig(endpoint)
        out.write(endpointUrlSignature(endpoint)).write(" ").writeBlock { out.write("return this.context.buildUrl(")
            .writeValue(config["uriTemplate"]).write(" , ").writeValue(config["pathVariables"])
            .writeLine(");") }
    }

    private fun endpointSignature(endpoint: Endpoint): String {
        val name = endpoint.name
        val parameters = parameterListCode(endpoint.parameters)
        val resultType = endpoint.responseBody?.let { qualifiedTypeName(it) } ?: "void"

        return format("%s(%s): %s<%s>", name, parameters, resultFunctor, resultType)
    }

    private fun endpointUrlSignature(endpoint: Endpoint): String {
        val name = endpoint.name + "Url"
        val parameters = parameterListCode(endpoint.parameters)
        val resultType = "string"

        return format("%s(%s): %s", name, parameters, resultType)
    }

    private fun qualifiedTypeName(type: ApiType): String = when {
        type is ApiType.Nullable -> qualifiedTypeName(type.type) + " | null"
        type is ApiType.Primitive -> type.toTypeScript()
        type is ApiType.Array -> qualifiedTypeName(type.elementType) + "[]"
        settings.isImported(ApiTypeName(type.toTypeScript())) -> type.toTypeScript()
        else -> type.toTypeScript()
    }

    private fun discriminatedUnionMemberType(unionType: ApiTypeName, memberType: ApiType) =
        "${unionType.name}_${memberType.toTypeScript()}"

    private fun parameterListCode(parameters: List<EndpointParameter>) =
        parameters.joinToString(", ") { p -> p.name + ": " + qualifiedTypeName(p.type) }

    protected open fun writePlatformSpecific() {}

    companion object {

        @JvmStatic
        protected fun createConfig(endpoint: Endpoint): Map<String, Any> {
            val config = LinkedHashMap<String, Any>()

            config["uriTemplate"] = endpoint.uriTemplate.toString()
            config["method"] = endpoint.method.toString()

            val pathVariables = endpoint.pathVariables
            if (!pathVariables.isEmpty())
                config["pathVariables"] = createPathVariablesMap(pathVariables)

            val requestParameters = endpoint.requestParameters
            if (!requestParameters.isEmpty())
                config["requestParams"] = createRequestParamMap(requestParameters)

            endpoint.requestBody?.let { body -> config["requestBody"] = serialize(body.name, body.type.unwrapNullable()) }
            endpoint.responseBody?.let { body -> config["responseType"] = typeDescriptor(body) }

            return config
        }

        private fun createRequestParamMap(parameters: Collection<EndpointRequestParamParameter>): Map<String, Any> {
            val result = LinkedHashMap<String, Any>()

            for (param in parameters)
                result[param.queryParameter] = serialize(param.name, param.type)

            return result
        }

        private fun createPathVariablesMap(pathVariables: List<EndpointPathVariableParameter>): Map<String, Any> {
            val result = LinkedHashMap<String, Any>()

            for (param in pathVariables)
                result[param.pathVariable] = serialize(param.name, param.type)

            return result
        }

        /**
         * Returns TypeScript code to serialize `variable` of given `type`
         * to transfer representation.
         */
        private fun serialize(variable: String, type: ApiType) =
            RawCode("this.context.serialize(" + variable + ", '" + typeDescriptor(type) + "')")

        private fun typeDescriptor(type: ApiType): String {
            // Use ApiType's native representation as type descriptor.
            // This method encapsulates the call to make it meaningful in this context.
            return type.unwrapNullable().toTypeScript()
        }
    }
}

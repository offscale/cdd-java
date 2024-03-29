package io.offscale;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Schema {
    private final String type;
    private final String strictType;
    private final ImmutableMap<String, Schema> properties;

    private final Schema arrayOfType;

    public Schema(String type) {
        this.type = type;
        this.strictType = type;
        this.properties = null;
        this.arrayOfType = null;
    }

    public Schema(String type, String strictType) {
        this.type = type;
        this.strictType = strictType;
        this.properties = null;
        this.arrayOfType = null;
    }

    public Schema(String type, String strictType, Map<String, Schema> properties) {
        this.type = type;
        this.strictType = strictType;
        this.properties = ImmutableMap.copyOf(properties);
        this.arrayOfType = null;
    }

    public Schema(String type, String strictType, Schema arrayOfType) {
        this.type = type;
        this.strictType = strictType;
        this.properties = null;
        this.arrayOfType = arrayOfType;
    }


    public String type() {
        return this.type;
    }

    public String strictType() {
        return this.strictType;
    }

    public Schema arrayOfType() {
        return this.arrayOfType;
    }

    public ImmutableMap<String, Schema> properties() {
        return properties;
    }

    private String toCodeAux(Schema schema, ClassOrInterfaceDeclaration parentClass) {
        if (schema.isObject()) {
            final ClassOrInterfaceDeclaration newClass = new ClassOrInterfaceDeclaration();
            schema.properties.forEach((name, propSchema) -> {
                toCodeAux(propSchema, newClass);
                newClass.addField(propSchema.type, name).setJavadocComment("Type of " + propSchema.strictType);
            });
            newClass.setName(schema.type);
            if (parentClass != null) {
                parentClass.addMember(newClass);
            }
            return newClass.toString();
        }

        if (schema.isArray()) {
            toCodeAux(schema.arrayOfType, parentClass);
        }

        return schema.type;
    }

    public String toCode() {
        assert this.isObject();
        return toCodeAux(this, null);
    }

    public static Schema parseSchema(JSONObject joSchema, Map<String, Schema> schemas, String type) {
        if (joSchema.has("properties")) {
            assert (!type.isEmpty());
            HashMap<String, Schema> schemaProperties = new HashMap<>();
            final List<String> propertyKeys = Lists.newArrayList(joSchema.getJSONObject("properties").keys());
            propertyKeys.forEach(key -> {
                        schemaProperties.put(key, parseSchema(
                                joSchema.getJSONObject("properties").getJSONObject(key),
                                schemas,
                                Utils.capitalizeFirstLetter(key)));
                    }
            );

            if (isNullValue(schemaProperties)) {
                return null;
            }

            return new Schema(type, type, schemaProperties);
        }

        if (joSchema.has("type") && joSchema.get("type").equals("array")) {
            Schema itemsSchema = parseSchema(joSchema.getJSONObject("items"), schemas, Utils.capitalizeFirstLetter(type));
            if (itemsSchema == null) {
                return null;
            }
            return new Schema(itemsSchema.type + "[]", itemsSchema.type + "[]", itemsSchema);
        }

        if (joSchema.has("type") && joSchema.has("format")) {
            return new Schema(Utils.getOpenAPIToJavaTypes().get(joSchema.get("format")), joSchema.getString("format"));
        }

        if (joSchema.has("type") && !joSchema.has("format")) {
            return new Schema(Utils.getOpenAPIToJavaTypes().get(joSchema.get("type")), joSchema.getString("type"));
        }

        assert (joSchema.has("$ref"));
        if (schemas.containsKey(parseSchemaRef(joSchema.getString("$ref")))) {
            return schemas.get(parseSchemaRef(joSchema.getString("$ref")));
        }

        return null;
    }

    private boolean equalsAux(Schema schema1, Schema schema2) {
        if (schema1.isObject() && schema2.isObject()) {
            if (!schema1.type().equals(schema2.type())) {
                return false;
            }

            for (String key: schema1.properties().keySet()) {
                if (!schema2.properties().containsKey(key)) {
                    return false;
                }

                if (!equalsAux(schema1.properties().get(key), schema2.properties().get(key))) {
                    return false;
                }
            }
            return true;
        }

        if (schema1.isArray() && schema2.isArray()) {
            return equalsAux(schema1.arrayOfType(), schema2.arrayOfType());
        }

        return schema1.type().equals(schema2.type);
    }

    @Override
    public boolean equals(Object schema) {
        if (this == schema) {
            return true;
        }

        if (schema instanceof Schema) {
            return equalsAux(this, (Schema) schema);
        }

        return false;
    }

    public boolean isObject() {
        return this.properties != null;
    }

    public boolean isArray() {
        return this.arrayOfType != null;
    }

    private static boolean isNullValue(HashMap<String, Schema> map) {
        for (Schema value : map.values()) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    private static String parseSchemaRef(String ref) {
        final Pattern pattern = Pattern.compile("#/components/schemas/(\\w+)");
        final Matcher matcher = pattern.matcher(ref);
        matcher.find();
        return matcher.group(1);
    }
}

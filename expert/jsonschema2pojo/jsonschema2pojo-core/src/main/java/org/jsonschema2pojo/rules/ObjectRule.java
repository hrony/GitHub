/**
 * Copyright © 2010-2017 Nokia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jsonschema2pojo.rules;

import android.os.Parcelable;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.codemodel.ClassType;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;

import org.jsonschema2pojo.AnnotationStyle;
import org.jsonschema2pojo.Schema;
import org.jsonschema2pojo.exception.ClassAlreadyExistsException;
import org.jsonschema2pojo.util.NameHelper;
import org.jsonschema2pojo.util.ParcelableHelper;
import org.jsonschema2pojo.util.SerializableHelper;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.jsonschema2pojo.rules.PrimitiveTypes.isPrimitive;
import static org.jsonschema2pojo.rules.PrimitiveTypes.primitiveType;
import static org.jsonschema2pojo.util.TypeUtil.resolveType;

/**
 * Applies the generation steps required for schemas of type "object".
 *
 * @see <a href=
 *      "http://tools.ietf.org/html/draft-zyp-json-schema-03#section-5.1">http:/
 *      /tools.ietf.org/html/draft-zyp-json-schema-03#section-5.1</a>
 */
public class ObjectRule implements Rule<JPackage, JType> {

    private final RuleFactory ruleFactory;
    private final ParcelableHelper parcelableHelper;

    protected ObjectRule(RuleFactory ruleFactory, ParcelableHelper parcelableHelper) {
        this.ruleFactory = ruleFactory;
        this.parcelableHelper = parcelableHelper;
    }

    /**
     * Applies this schema rule to take the required code generation steps.
     * <p>
     * When this rule is applied for schemas of type object, the properties of
     * the schema are used to generate a new Java class and determine its
     * characteristics. See other implementers of {@link Rule} for details.
     */
    @Override
    public JType apply(String nodeName, JsonNode node, JPackage _package, Schema schema) {

        JType superType = getSuperType(nodeName, node, _package, schema);

        if (superType.isPrimitive() || isFinal(superType)) {
            return superType;
        }

        JDefinedClass jclass;
        try {
            jclass = createClass(nodeName, node, _package);
        } catch (ClassAlreadyExistsException e) {
            return e.getExistingClass();
        }

        jclass._extends((JClass) superType);

        schema.setJavaTypeIfEmpty(jclass);

        if (node.has("deserializationClassProperty")) {
            addJsonTypeInfoAnnotation(jclass, node);
        }

        if (node.has("title")) {
            ruleFactory.getTitleRule().apply(nodeName, node.get("title"), jclass, schema);
        }

        if (node.has("description")) {
            ruleFactory.getDescriptionRule().apply(nodeName, node.get("description"), jclass, schema);
        }

        ruleFactory.getPropertiesRule().apply(nodeName, node.get("properties"), jclass, schema);

        if (node.has("javaInterfaces")) {
            addInterfaces(jclass, node.get("javaInterfaces"));
        }

        ruleFactory.getAdditionalPropertiesRule().apply(nodeName, node.get("additionalProperties"), jclass, schema);

        ruleFactory.getDynamicPropertiesRule().apply(nodeName, node.get("properties"), jclass, schema);

        if (node.has("required")) {
            ruleFactory.getRequiredArrayRule().apply(nodeName, node.get("required"), jclass, schema);
        }

        if (ruleFactory.getGenerationConfig().isIncludeToString()) {
            addToString(jclass);
        }

        if (ruleFactory.getGenerationConfig().isIncludeHashcodeAndEquals()) {
            addHashCode(jclass, node);
            addEquals(jclass, node);
        }

        if (ruleFactory.getGenerationConfig().isParcelable()) {
            addParcelSupport(jclass);
        }

        if (ruleFactory.getGenerationConfig().isIncludeConstructors()) {
            addConstructors(jclass, node, schema, ruleFactory.getGenerationConfig().isConstructorsRequiredPropertiesOnly());
        }

        if (ruleFactory.getGenerationConfig().isSerializable()) {
            SerializableHelper.addSerializableSupport(jclass);
        }

        return jclass;

    }

    private void addParcelSupport(JDefinedClass jclass) {
        jclass._implements(Parcelable.class);

        parcelableHelper.addWriteToParcel(jclass);
        parcelableHelper.addDescribeContents(jclass);
        parcelableHelper.addCreator(jclass);
        parcelableHelper.addConstructorFromParcel(jclass);
        // #742 : includeConstructors will include the default constructor
        if (!ruleFactory.getGenerationConfig().isIncludeConstructors()) {
            // Add empty constructor
            jclass.constructor(JMod.PUBLIC);
        }
    }

    /**
     * Retrieve the list of properties to go in the constructor from node. This
     * is all properties listed in node["properties"] if ! onlyRequired, and
     * only required properties if onlyRequired.
     *
     * @param node
     * @return
     */
    private LinkedHashSet<String> getConstructorProperties(JsonNode node, boolean onlyRequired) {

        if (!node.has("properties")) {
            return new LinkedHashSet<String>();
        }

        LinkedHashSet<String> rtn = new LinkedHashSet<String>();
        Set<String> draft4RequiredProperties = new HashSet<String>();

        // setup the set of required properties for draft4 style "required"
        if (onlyRequired && node.has("required")) {
            JsonNode requiredArray =  node.get("required");
            if (requiredArray.isArray()) {
                for (JsonNode requiredEntry: requiredArray) {
                    if (requiredEntry.isTextual()) {
                        draft4RequiredProperties.add(requiredEntry.asText());
                    }
                }
            }
        }

        NameHelper nameHelper = ruleFactory.getNameHelper();
        for (Iterator<Map.Entry<String, JsonNode>> properties = node.get("properties").fields(); properties.hasNext();) {
            Map.Entry<String, JsonNode> property = properties.next();

            JsonNode propertyObj = property.getValue();
            if (onlyRequired) {
                // draft3 style
                if (propertyObj.has("required") && propertyObj.get("required").asBoolean()) {
                    rtn.add(nameHelper.getPropertyName(property.getKey(), property.getValue()));
                }

                // draft4 style
                if (draft4RequiredProperties.contains(property.getKey())) {
                    rtn.add(nameHelper.getPropertyName(property.getKey(), property.getValue()));
                }
            } else {
                rtn.add(nameHelper.getPropertyName(property.getKey(), property.getValue()));
            }
        }
        return rtn;
    }

    /**
     * Recursive, walks the schema tree and assembles a list of all properties of this schema's super schemas
     */
    private LinkedHashSet<String> getSuperTypeConstructorPropertiesRecursive(JsonNode node, Schema schema, boolean onlyRequired) {
        Schema superTypeSchema = getSuperSchema(node, schema, true);

        if (superTypeSchema == null) {
            return new LinkedHashSet<String>();
        }

        JsonNode superSchemaNode = superTypeSchema.getContent();

        LinkedHashSet<String> rtn = getConstructorProperties(superSchemaNode, onlyRequired);
        rtn.addAll(getSuperTypeConstructorPropertiesRecursive(superSchemaNode, superTypeSchema, onlyRequired));

        return rtn;
    }

    /**
     * Creates a new Java class that will be generated.
     *
     * @param nodeName
     *            the node name which may be used to dictate the new class name
     * @param node
     *            the node representing the schema that caused the need for a
     *            new class. This node may include a 'javaType' property which
     *            if present will override the fully qualified name of the newly
     *            generated class.
     * @param _package
     *            the package which may contain a new class after this method
     *            call
     * @return a reference to a newly created class
     * @throws ClassAlreadyExistsException
     *             if the given arguments cause an attempt to create a class
     *             that already exists, either on the classpath or in the
     *             current map of classes to be generated.
     */
    private JDefinedClass createClass(String nodeName, JsonNode node, JPackage _package) throws ClassAlreadyExistsException {

        JDefinedClass newType;

        try {
            boolean usePolymorphicDeserialization = usesPolymorphicDeserialization(node);
            if (node.has("javaType")) {
                String fqn = substringBefore(node.get("javaType").asText(), "<");

                if (isPrimitive(fqn, _package.owner())) {
                    throw new ClassAlreadyExistsException(primitiveType(fqn, _package.owner()));
                }
                JClass existingClass;

                try {
                    _package.owner().ref(Thread.currentThread().getContextClassLoader().loadClass(fqn));
                    existingClass = resolveType(_package, fqn + (node.get("javaType").asText().contains("<") ? "<" + substringAfter(node.get("javaType").asText(), "<") : ""));

                    throw new ClassAlreadyExistsException(existingClass);
                } catch (ClassNotFoundException e) {

                }

                int index = fqn.lastIndexOf(".") + 1;
                if (index >= 0 && index < fqn.length()) {
                    fqn = fqn.substring(0, index) + ruleFactory.getGenerationConfig().getClassNamePrefix() + fqn.substring(index) + ruleFactory.getGenerationConfig().getClassNameSuffix();
                }

                try {

                    _package.owner().ref(Thread.currentThread().getContextClassLoader().loadClass(fqn));
                    existingClass = resolveType(_package, fqn + (node.get("javaType").asText().contains("<") ? "<" + substringAfter(node.get("javaType").asText(), "<") : ""));

                    throw new ClassAlreadyExistsException(existingClass);
                } catch (ClassNotFoundException e) {

                }
                if (usePolymorphicDeserialization) {
                    newType = _package.owner()._class(JMod.PUBLIC, fqn, ClassType.CLASS);
                } else {
                    newType = _package.owner()._class(fqn);
                }
            } else {
                if (usePolymorphicDeserialization) {
                    newType = _package._class(JMod.PUBLIC, getClassName(nodeName, node, _package), ClassType.CLASS);
                } else {
                    newType = _package._class(getClassName(nodeName, node, _package));
                }
            }
        } catch (JClassAlreadyExistsException e) {
            throw new ClassAlreadyExistsException(e.getExistingClass());
        }

        ruleFactory.getAnnotator().propertyInclusion(newType, node);

        return newType;

    }

    private boolean isFinal(JType superType) {
        try {
            Class<?> javaClass = Class.forName(superType.fullName());
            return Modifier.isFinal(javaClass.getModifiers());
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private JType getSuperType(String nodeName, JsonNode node, JPackage jPackage, Schema schema) {
        if (node.has("extends") && node.has("extendsJavaClass")) {
            throw new IllegalStateException("'extends' and 'extendsJavaClass' defined simultaneously");
        }

        JType superType = jPackage.owner().ref(Object.class);
        Schema superTypeSchema = getSuperSchema(node, schema, false);
        if (superTypeSchema != null) {
            superType = ruleFactory.getSchemaRule().apply(nodeName + "Parent", node.get("extends"), jPackage, superTypeSchema);
        } else if (node.has("extendsJavaClass")) {
            superType = resolveType(jPackage, node.get("extendsJavaClass").asText());
        }

        return superType;
    }

    private Schema getSuperSchema(JsonNode node, Schema schema, boolean followRefs) {
        if (node.has("extends")) {
            String path;
            if (schema.getId().getFragment() == null) {
                path = "#extends";
            } else {
                path = "#" + schema.getId().getFragment() + "/extends";
            }

            Schema superSchema = ruleFactory.getSchemaStore().create(schema, path, ruleFactory.getGenerationConfig().getRefFragmentPathDelimiters());

            if (followRefs) {
                superSchema = resolveSchemaRefsRecursive(superSchema);
            }

            return superSchema;
        }
        return null;
    }

    private Schema resolveSchemaRefsRecursive(Schema schema) {
        JsonNode schemaNode = schema.getContent();
        if (schemaNode.has("$ref")) {
            schema = ruleFactory.getSchemaStore().create(schema, schemaNode.get("$ref").asText(), ruleFactory.getGenerationConfig().getRefFragmentPathDelimiters());
            return resolveSchemaRefsRecursive(schema);
        }
        return schema;
    }

    private void addJsonTypeInfoAnnotation(JDefinedClass jclass, JsonNode node) {
        if (ruleFactory.getGenerationConfig().getAnnotationStyle() == AnnotationStyle.JACKSON2) {
            String annotationName = node.get("deserializationClassProperty").asText();
            JAnnotationUse jsonTypeInfo = jclass.annotate(JsonTypeInfo.class);
            jsonTypeInfo.param("use", JsonTypeInfo.Id.CLASS);
            jsonTypeInfo.param("include", JsonTypeInfo.As.PROPERTY);
            jsonTypeInfo.param("property", annotationName);
        }
    }

    private void addToString(JDefinedClass jclass) {
        Map<String, JFieldVar> fields = jclass.fields();
        JMethod toString = jclass.method(JMod.PUBLIC, String.class, "toString");
        Set<String> excludes = new HashSet<String>(Arrays.asList(ruleFactory.getGenerationConfig().getToStringExcludes()));

        JBlock body = toString.body();
        Class<?> toStringBuilder = ruleFactory.getGenerationConfig().isUseCommonsLang3() ? org.apache.commons.lang3.builder.ToStringBuilder.class : org.apache.commons.lang.builder.ToStringBuilder.class;
        JClass toStringBuilderClass = jclass.owner().ref(toStringBuilder);
        JInvocation toStringBuilderInvocation = JExpr._new(toStringBuilderClass).arg(JExpr._this());

        if (!jclass._extends().fullName().equals(Object.class.getName())) {
            toStringBuilderInvocation = toStringBuilderInvocation.invoke("appendSuper").arg(JExpr._super().invoke("toString"));
        }

        for (JFieldVar fieldVar : fields.values()) {
            if (excludes.contains(fieldVar.name()) || (fieldVar.mods().getValue() & JMod.STATIC) == JMod.STATIC) {
                continue;
            }
            toStringBuilderInvocation = toStringBuilderInvocation.invoke("append").arg(fieldVar.name()).arg(fieldVar);
        }

        body._return(toStringBuilderInvocation.invoke("toString"));

        toString.annotate(Override.class);
    }

    private void addHashCode(JDefinedClass jclass, JsonNode node) {
        Map<String, JFieldVar> fields = removeFieldsExcludedFromEqualsAndHashCode(jclass.fields(), node);

        JMethod hashCode = jclass.method(JMod.PUBLIC, int.class, "hashCode");

        Class<?> hashCodeBuilder = ruleFactory.getGenerationConfig().isUseCommonsLang3() ? org.apache.commons.lang3.builder.HashCodeBuilder.class : org.apache.commons.lang.builder.HashCodeBuilder.class;

        JBlock body = hashCode.body();
        JClass hashCodeBuilderClass = jclass.owner().ref(hashCodeBuilder);
        JInvocation hashCodeBuilderInvocation = JExpr._new(hashCodeBuilderClass);

        if (!jclass._extends().fullName().equals(Object.class.getName())) {
            hashCodeBuilderInvocation = hashCodeBuilderInvocation.invoke("appendSuper").arg(JExpr._super().invoke("hashCode"));
        }

        for (JFieldVar fieldVar : fields.values()) {
            if ((fieldVar.mods().getValue() & JMod.STATIC) == JMod.STATIC) {
                continue;
            }
            hashCodeBuilderInvocation = hashCodeBuilderInvocation.invoke("append").arg(fieldVar);
        }

        body._return(hashCodeBuilderInvocation.invoke("toHashCode"));

        hashCode.annotate(Override.class);
    }

    private Map<String, JFieldVar> removeFieldsExcludedFromEqualsAndHashCode(Map<String, JFieldVar> fields, JsonNode node) {
        Map<String, JFieldVar> filteredFields = new HashMap<String, JFieldVar>(fields);

        JsonNode properties = node.get("properties");

        if (properties != null) {
            if (node.has("excludedFromEqualsAndHashCode")) {
                JsonNode excludedArray = node.get("excludedFromEqualsAndHashCode");

                for (Iterator<JsonNode> iterator = excludedArray.elements(); iterator.hasNext(); ) {
                    String excludedPropertyName = iterator.next().asText();
                    JsonNode excludedPropertyNode = properties.get(excludedPropertyName);
                    filteredFields.remove(ruleFactory.getNameHelper().getPropertyName(excludedPropertyName, excludedPropertyNode));
                }
            }

            for (Iterator<Map.Entry<String, JsonNode>> iterator = properties.fields(); iterator.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                String propertyName = entry.getKey();
                JsonNode propertyNode = entry.getValue();

                if (propertyNode.has("excludedFromEqualsAndHashCode") &&
                        propertyNode.get("excludedFromEqualsAndHashCode").asBoolean()) {
                    filteredFields.remove(ruleFactory.getNameHelper().getPropertyName(propertyName, propertyNode));
                }
            }
        }

        return filteredFields;
    }

    private void addConstructors(JDefinedClass jclass, JsonNode node, Schema schema, boolean onlyRequired) {

        LinkedHashSet<String> classProperties = getConstructorProperties(node, onlyRequired);
        LinkedHashSet<String> combinedSuperProperties = getSuperTypeConstructorPropertiesRecursive(node, schema, onlyRequired);

        // no properties to put in the constructor => default constructor is good enough.
        if (classProperties.isEmpty() && combinedSuperProperties.isEmpty()) {
            return;
        }

        // add a no-args constructor for serialization purposes
        JMethod noargsConstructor = jclass.constructor(JMod.PUBLIC);
        noargsConstructor.javadoc().add("No args constructor for use in serialization");

        // add the public constructor with property parameters
        JMethod fieldsConstructor = jclass.constructor(JMod.PUBLIC);
        JBlock constructorBody = fieldsConstructor.body();
        JInvocation superInvocation = constructorBody.invoke("super");

        Map<String, JFieldVar> fields = jclass.fields();
        Map<String, JVar> classFieldParams = new HashMap<String, JVar>();

        for (String property : classProperties) {
            JFieldVar field = fields.get(property);

            if (field == null) {
                throw new IllegalStateException("Property " + property + " hasn't been added to JDefinedClass before calling addConstructors");
            }

            fieldsConstructor.javadoc().addParam(property);
            JVar param = fieldsConstructor.param(field.type(), field.name());
            constructorBody.assign(JExpr._this().ref(field), param);
            classFieldParams.put(property, param);
        }

        List<JVar> superConstructorParams = new ArrayList<JVar>();


        for (String property : combinedSuperProperties) {
            JFieldVar field = searchSuperClassesForField(property, jclass);

            if (field == null) {
                throw new IllegalStateException("Property " + property + " hasn't been added to JDefinedClass before calling addConstructors");
            }

            JVar param = classFieldParams.get(property);

            if (param == null) {
                param = fieldsConstructor.param(field.type(), field.name());
            }

            fieldsConstructor.javadoc().addParam(property);
            superConstructorParams.add(param);
        }

        for (JVar param : superConstructorParams) {
            superInvocation.arg(param);
        }
    }

    private static JDefinedClass definedClassOrNullFromType(JType type)
    {
        if (type == null || type.isPrimitive())
        {
            return null;
        }
        JClass fieldClass = type.boxify();
        JPackage jPackage = fieldClass._package();
        return jPackage._getClass(fieldClass.name());
    }

    /**
     * This is recursive with searchClassAndSuperClassesForField
     */
    private JFieldVar searchSuperClassesForField(String property, JDefinedClass jclass) {
        JClass superClass = jclass._extends();
        JDefinedClass definedSuperClass = definedClassOrNullFromType(superClass);
        if (definedSuperClass == null) {
            return null;
        }
        return searchClassAndSuperClassesForField(property, definedSuperClass);
    }

    private JFieldVar searchClassAndSuperClassesForField(String property, JDefinedClass jclass) {
        Map<String, JFieldVar> fields = jclass.fields();
        JFieldVar field = fields.get(property);
        if (field == null) {
            return searchSuperClassesForField(property, jclass);
        }
        return field;
    }

    private void addEquals(JDefinedClass jclass, JsonNode node) {
        Map<String, JFieldVar> fields = removeFieldsExcludedFromEqualsAndHashCode(jclass.fields(), node);

        JMethod equals = jclass.method(JMod.PUBLIC, boolean.class, "equals");
        JVar otherObject = equals.param(Object.class, "other");

        Class<?> equalsBuilder = ruleFactory.getGenerationConfig().isUseCommonsLang3() ? org.apache.commons.lang3.builder.EqualsBuilder.class : org.apache.commons.lang.builder.EqualsBuilder.class;

        JBlock body = equals.body();

        body._if(otherObject.eq(JExpr._this()))._then()._return(JExpr.TRUE);
        body._if(otherObject._instanceof(jclass).eq(JExpr.FALSE))._then()._return(JExpr.FALSE);

        JVar rhsVar = body.decl(jclass, "rhs").init(JExpr.cast(jclass, otherObject));
        JClass equalsBuilderClass = jclass.owner().ref(equalsBuilder);
        JInvocation equalsBuilderInvocation = JExpr._new(equalsBuilderClass);

        if (!jclass._extends().fullName().equals(Object.class.getName())) {
            equalsBuilderInvocation = equalsBuilderInvocation.invoke("appendSuper").arg(JExpr._super().invoke("equals").arg(otherObject));
        }

        for (JFieldVar fieldVar : fields.values()) {
            if ((fieldVar.mods().getValue() & JMod.STATIC) == JMod.STATIC) {
                continue;
            }
            equalsBuilderInvocation = equalsBuilderInvocation.invoke("append")
                    .arg(fieldVar)
                    .arg(rhsVar.ref(fieldVar.name()));
        }

        JInvocation reflectionEquals = jclass.owner().ref(equalsBuilder).staticInvoke("reflectionEquals");
        reflectionEquals.arg(JExpr._this());
        reflectionEquals.arg(otherObject);

        body._return(equalsBuilderInvocation.invoke("isEquals"));

        equals.annotate(Override.class);
    }

    private void addInterfaces(JDefinedClass jclass, JsonNode javaInterfaces) {
        for (JsonNode i : javaInterfaces) {
            jclass._implements(resolveType(jclass._package(), i.asText()));
        }
    }

    private String getClassName(String nodeName, JsonNode node, JPackage _package) {
        String prefix = ruleFactory.getGenerationConfig().getClassNamePrefix();
        String suffix = ruleFactory.getGenerationConfig().getClassNameSuffix();
        String fieldName = ruleFactory.getNameHelper().getFieldName(nodeName, node);
        String capitalizedFieldName = capitalize(fieldName);
        String fullFieldName = createFullFieldName(capitalizedFieldName, prefix, suffix);

        String className = ruleFactory.getNameHelper().replaceIllegalCharacters(fullFieldName);
        String normalizedName = ruleFactory.getNameHelper().normalizeName(className);
        return makeUnique(normalizedName, _package);
    }

    private String createFullFieldName(String nodeName, String prefix, String suffix) {
        String returnString = nodeName;
        if (prefix != null) {
            returnString = prefix + returnString;
        }

        if (suffix != null) {
            returnString = returnString + suffix;
        }

        return returnString;
    }

    private String makeUnique(String className, JPackage _package) {
        try {
            JDefinedClass _class = _package._class(className);
            _package.remove(_class);
            return className;
        } catch (JClassAlreadyExistsException e) {
            return makeUnique(className + "_", _package);
        }
    }

    private boolean usesPolymorphicDeserialization(JsonNode node) {
        if (ruleFactory.getGenerationConfig().getAnnotationStyle() == AnnotationStyle.JACKSON2) {
            return node.has("deserializationClassProperty");
        }
        return false;
    }

}
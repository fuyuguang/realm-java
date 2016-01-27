/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.processor;

import com.squareup.javawriter.JavaWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

public class RealmProxyClassGenerator {
    private ProcessingEnvironment processingEnvironment;
    private ClassMetaData metadata;
    private final String className;

    public RealmProxyClassGenerator(ProcessingEnvironment processingEnvironment, ClassMetaData metadata) {
        this.processingEnvironment = processingEnvironment;
        this.metadata = metadata;
        this.className = metadata.getSimpleClassName();
    }

    public void generate() throws IOException, UnsupportedOperationException {
        String qualifiedGeneratedClassName = String.format("%s.%s", Constants.REALM_PACKAGE_NAME, Utils.getProxyClassName(className));
        JavaFileObject sourceFile = processingEnvironment.getFiler().createSourceFile(qualifiedGeneratedClassName);
        JavaWriter writer = new JavaWriter(new BufferedWriter(sourceFile.openWriter()));

        // Set source code indent to 4 spaces
        writer.setIndent("    ");

        writer.emitPackage(Constants.REALM_PACKAGE_NAME)
                .emitEmptyLine();

        ArrayList<String> imports = new ArrayList<String>();
        imports.add("android.util.JsonReader");
        imports.add("android.util.JsonToken");
        imports.add("io.realm.RealmFieldType");
        imports.add("io.realm.exceptions.RealmException");
        imports.add("io.realm.exceptions.RealmMigrationNeededException");
        imports.add("io.realm.internal.ColumnInfo");
        imports.add("io.realm.internal.RealmObjectProxy");
        imports.add("io.realm.internal.Table");
        imports.add("io.realm.internal.TableOrView");
        imports.add("io.realm.internal.ImplicitTransaction");
        imports.add("io.realm.internal.LinkView");
        imports.add("io.realm.internal.android.JsonUtils");
        imports.add("java.io.IOException");
        imports.add("java.lang.reflect.Field");
        imports.add("java.util.ArrayList");
        imports.add("java.util.Collections");
        imports.add("java.util.List");
        imports.add("java.util.Date");
        imports.add("java.util.Map");
        imports.add("java.util.HashMap");
        imports.add("org.json.JSONObject");
        imports.add("org.json.JSONException");
        imports.add("org.json.JSONArray");
        imports.add(metadata.getFullyQualifiedClassName());

        for (VariableElement field : metadata.getFields()) {
            String fieldTypeName = "";
            if (Utils.isRealmObject(field)) { // Links
                fieldTypeName = field.asType().toString();
            } else if (Utils.isRealmList(field)) { // LinkLists
                fieldTypeName = ((DeclaredType) field.asType()).getTypeArguments().get(0).toString();
            }
            if (!fieldTypeName.isEmpty() && !imports.contains(fieldTypeName)) {
                imports.add(fieldTypeName);
            }
        }
        Collections.sort(imports);
        writer.emitImports(imports);
        writer.emitEmptyLine();

        // Begin the class definition
        writer.beginType(
                qualifiedGeneratedClassName, // full qualified name of the item to generate
                "class",                     // the type of the item
                EnumSet.of(Modifier.PUBLIC), // modifiers to apply
                className,                   // class to extend
                "RealmObjectProxy")          // interfaces to implement
                .emitEmptyLine();

        emitColumnIndicesClass(writer);

        emitClassFields(writer);
        emitConstructor(writer);
        emitAccessors(writer);
        emitInitTableMethod(writer);
        emitValidateTableMethod(writer);
        emitGetTableNameMethod(writer);
        emitGetFieldNamesMethod(writer);
        emitCreateOrUpdateUsingJsonObject(writer);
        emitCreateUsingJsonStream(writer);
        emitCopyOrUpdateMethod(writer);
        emitCopyMethod(writer);
        emitCreateDetachedCopyMethod(writer);
        emitUpdateMethod(writer);
        if (!metadata.containsToString()) {
            emitToStringMethod(writer);
        }
        if (!metadata.containsHashCode()) {
            emitHashcodeMethod(writer);
        }
        if (!metadata.containsEquals()) {
            emitEqualsMethod(writer);
        }

        // End the class definition
        writer.endType();
        writer.close();
    }

    private void emitColumnIndicesClass(JavaWriter writer) throws IOException {
        writer.beginType(
                columnInfoClassName(),                       // full qualified name of the item to generate
                "class",                                     // the type of the item
                EnumSet.of(Modifier.STATIC, Modifier.FINAL), // modifiers to apply
                "ColumnInfo")                                // base class
                .emitEmptyLine();

        // fields
        for (VariableElement variableElement : metadata.getFields()) {
            writer.emitField("long", columnIndexVarName(variableElement),
                    EnumSet.of(Modifier.PUBLIC, Modifier.FINAL));
        }
        writer.emitEmptyLine();

        // constructor
        writer.beginConstructor(EnumSet.noneOf(Modifier.class),
                "String", "path",
                "Table", "table");
        writer.emitStatement("final Map<String, Long> indicesMap = new HashMap<String, Long>(%s)",
                metadata.getFields().size());
        for (VariableElement variableElement : metadata.getFields()) {
            final String columnName = variableElement.getSimpleName().toString();
            final String columnIndexVarName = columnIndexVarName(variableElement);
            writer.emitStatement("this.%s = getValidColumnIndex(path, table, \"%s\", \"%s\")",
                    columnIndexVarName, className, columnName);
            writer.emitStatement("indicesMap.put(\"%s\", this.%s)", columnName, columnIndexVarName);
            writer.emitEmptyLine();
        }
        writer.emitStatement("setIndicesMap(indicesMap)");
        writer.endConstructor();

        writer.endType();
        writer.emitEmptyLine();
    }

    private void emitClassFields(JavaWriter writer) throws IOException {
        writer.emitField(columnInfoClassName(), "columnInfo", EnumSet.of(Modifier.PRIVATE, Modifier.FINAL));
        for (VariableElement variableElement : metadata.getFields()) {
            if (Utils.isRealmList(variableElement)) {
                String genericType = Utils.getGenericType(variableElement);
                writer.emitField("RealmList<" + genericType + ">", variableElement.getSimpleName().toString() + "RealmList", EnumSet.of(Modifier.PRIVATE));
            }
        }

        writer.emitField("List<String>", "FIELD_NAMES", EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL));
        writer.beginInitializer(true);
        writer.emitStatement("List<String> fieldNames = new ArrayList<String>()");
        for (VariableElement field : metadata.getFields()) {
            writer.emitStatement("fieldNames.add(\"%s\")", field.getSimpleName().toString());
        }
        writer.emitStatement("FIELD_NAMES = Collections.unmodifiableList(fieldNames)");
        writer.endInitializer();
        writer.emitEmptyLine();
    }

    private void emitConstructor(JavaWriter writer) throws IOException {
        // FooRealmProxy(ColumnInfo)
        writer.beginConstructor(EnumSet.noneOf(Modifier.class), "ColumnInfo", "columnInfo");
        writer.emitStatement("this.columnInfo = (%s) columnInfo", columnInfoClassName());
        writer.endConstructor();
        writer.emitEmptyLine();
    }

    private void emitAccessors(JavaWriter writer) throws IOException {
        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String fieldTypeCanonicalName = field.asType().toString();

            if (Constants.JAVA_TO_REALM_TYPES.containsKey(fieldTypeCanonicalName)) {
                /**
                 * Primitives and boxed types
                 */
                String realmType = Constants.JAVA_TO_REALM_TYPES.get(fieldTypeCanonicalName);

                // Getter
                writer.emitAnnotation("SuppressWarnings", "\"cast\"");
                writer.beginMethod(fieldTypeCanonicalName, metadata.getGetter(fieldName), EnumSet.of(Modifier.PUBLIC));
                writer.emitStatement("realm.checkIfValid()");

                // For String and bytes[], null value will be returned by JNI code. Try to save one JNI call here.
                if (metadata.isNullable(field) && !Utils.isString(field) && !Utils.isByteArray(field)) {
                    writer.beginControlFlow("if (row.isNull(%s))", fieldIndexVariableReference(field));
                    writer.emitStatement("return null");
                    writer.endControlFlow();
                }

                // For Boxed types, this should be the corresponding primitive types. Others remain the same.
                String castingBackType;
                if (Utils.isBoxedType(field.asType().toString())) {
                    Types typeUtils = processingEnvironment.getTypeUtils();
                    castingBackType = typeUtils.unboxedType(field.asType()).toString();
                } else {
                    castingBackType = fieldTypeCanonicalName;
                }
                writer.emitStatement(
                        "return (%s) row.get%s(%s)",
                        castingBackType, realmType, fieldIndexVariableReference(field));
                writer.endMethod();
                writer.emitEmptyLine();

                // Setter
                writer.beginMethod("void", metadata.getSetter(fieldName), EnumSet.of(Modifier.PUBLIC), fieldTypeCanonicalName, "value");
                writer.emitStatement("realm.checkIfValid()");
                // Although setting null value for String and bytes[] can be handled by the JNI code, we still generate the same code here.
                // Compared with getter, null value won't trigger more native calls in setter which is relatively cheaper.
                if (metadata.isNullable(field)) {
                    writer.beginControlFlow("if (value == null)")
                        .emitStatement("row.setNull(%s)", fieldIndexVariableReference(field))
                        .emitStatement("return")
                    .endControlFlow();
                } else if (!metadata.isNullable(field) && !Utils.isPrimitiveType(field)) {
                    // Same reason, throw IAE earlier.
                    writer
                        .beginControlFlow("if (value == null)")
                            .emitStatement(Constants.STATEMENT_EXCEPTION_ILLEGAL_NULL_VALUE, fieldName)
                        .endControlFlow();
                }
                writer.emitStatement(
                        "row.set%s(%s, value)",
                        realmType, fieldIndexVariableReference(field));
                writer.endMethod();
            } else if (Utils.isRealmObject(field)) {
                /**
                 * Links
                 */

                // Getter
                writer.beginMethod(fieldTypeCanonicalName, metadata.getGetter(fieldName), EnumSet.of(Modifier.PUBLIC));
                writer.emitStatement("realm.checkIfValid()");
                writer.beginControlFlow("if (row.isNullLink(%s))", fieldIndexVariableReference(field));
                        writer.emitStatement("return null");
                        writer.endControlFlow();
                writer.emitStatement("return realm.get(%s.class, row.getLink(%s))",
                        fieldTypeCanonicalName, fieldIndexVariableReference(field));
                writer.endMethod();
                writer.emitEmptyLine();

                // Setter
                writer.beginMethod("void", metadata.getSetter(fieldName), EnumSet.of(Modifier.PUBLIC), fieldTypeCanonicalName, "value");
                writer.emitStatement("realm.checkIfValid()");
                writer.beginControlFlow("if (value == null)");
                    writer.emitStatement("row.nullifyLink(%s)", fieldIndexVariableReference(field));
                    writer.emitStatement("return");
                writer.endControlFlow();
                writer.beginControlFlow("if (!value.isValid())");
                    writer.emitStatement("throw new IllegalArgumentException(\"'value' is not a valid managed object.\")");
                writer.endControlFlow();
                writer.beginControlFlow("if (value.realm != this.realm)");
                    writer.emitStatement("throw new IllegalArgumentException(\"'value' belongs to a different Realm.\")");
                writer.endControlFlow();
                writer.emitStatement("row.setLink(%s, value.row.getIndex())", fieldIndexVariableReference(field));
                writer.endMethod();
            } else if (Utils.isRealmList(field)) {
                /**
                 * LinkLists
                 */
                String genericType = Utils.getGenericType(field);

                // Getter
                writer.beginMethod(fieldTypeCanonicalName, metadata.getGetter(fieldName), EnumSet.of(Modifier.PUBLIC));
                writer.emitStatement("realm.checkIfValid()");
                writer.emitSingleLineComment("use the cached value if available");
                writer.beginControlFlow("if (" + fieldName + "RealmList != null)");
                        writer.emitStatement("return " + fieldName + "RealmList");
                writer.nextControlFlow("else");
                    writer.emitStatement("LinkView linkView = row.getLinkList(%s)", fieldIndexVariableReference(field));
                    writer.emitStatement(fieldName + "RealmList = new RealmList<%s>(%s.class, linkView, realm)",
                        genericType, genericType);
                    writer.emitStatement("return " + fieldName + "RealmList");
                writer.endControlFlow();

                writer.endMethod();
                writer.emitEmptyLine();

                // Setter
                writer.beginMethod("void", metadata.getSetter(fieldName), EnumSet.of(Modifier.PUBLIC), fieldTypeCanonicalName, "value");
                writer.emitStatement("realm.checkIfValid()");
                writer.emitStatement("LinkView links = row.getLinkList(%s)", fieldIndexVariableReference(field));
                writer.emitStatement("links.clear()");
                writer.beginControlFlow("if (value == null)");
                    writer.emitStatement("return");
                writer.endControlFlow();
                writer.beginControlFlow("for (RealmObject linkedObject : (RealmList<? extends RealmObject>) value)");
                    writer.beginControlFlow("if (!linkedObject.isValid())");
                        writer.emitStatement("throw new IllegalArgumentException(\"Each element of 'value' must be a valid managed object.\")");
                    writer.endControlFlow();
                    writer.beginControlFlow("if (linkedObject.realm != this.realm)");
                        writer.emitStatement("throw new IllegalArgumentException(\"Each element of 'value' must belong to the same Realm.\")");
                    writer.endControlFlow();
                    writer.emitStatement("links.add(linkedObject.row.getIndex())");
                writer.endControlFlow();
                writer.endMethod();
            } else {
                throw new UnsupportedOperationException(
                        String.format("Type %s of field %s is not supported", fieldTypeCanonicalName, fieldName));
            }
            writer.emitEmptyLine();
        }
    }

    private void emitInitTableMethod(JavaWriter writer) throws IOException {
        writer.beginMethod(
                "Table", // Return type
                "initTable", // Method name
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), // Modifiers
                "ImplicitTransaction", "transaction"); // Argument type & argument name

        writer.beginControlFlow("if (!transaction.hasTable(\"" + Constants.TABLE_PREFIX + this.className + "\"))");
        writer.emitStatement("Table table = transaction.getTable(\"%s%s\")", Constants.TABLE_PREFIX, this.className);

        // For each field generate corresponding table index constant
        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String fieldTypeCanonicalName = field.asType().toString();
            String fieldTypeSimpleName = Utils.getFieldTypeSimpleName(field);

            if (Constants.JAVA_TO_REALM_TYPES.containsKey(fieldTypeCanonicalName)) {
                String nullableFlag;
                if (metadata.isNullable(field)) {
                    nullableFlag = "Table.NULLABLE";
                } else {
                    nullableFlag = "Table.NOT_NULLABLE";
                }
                writer.emitStatement("table.addColumn(%s, \"%s\", %s)",
                        Constants.JAVA_TO_COLUMN_TYPES.get(fieldTypeCanonicalName),
                        fieldName, nullableFlag);
            } else if (Utils.isRealmObject(field)) {
                writer.beginControlFlow("if (!transaction.hasTable(\"%s%s\"))", Constants.TABLE_PREFIX, fieldTypeSimpleName);
                writer.emitStatement("%s%s.initTable(transaction)", fieldTypeSimpleName, Constants.PROXY_SUFFIX);
                writer.endControlFlow();
                writer.emitStatement("table.addColumnLink(RealmFieldType.OBJECT, \"%s\", transaction.getTable(\"%s%s\"))",
                        fieldName, Constants.TABLE_PREFIX, fieldTypeSimpleName);
            } else if (Utils.isRealmList(field)) {
                String genericType = Utils.getGenericType(field);
                writer.beginControlFlow("if (!transaction.hasTable(\"%s%s\"))", Constants.TABLE_PREFIX, genericType);
                writer.emitStatement("%s%s.initTable(transaction)", genericType, Constants.PROXY_SUFFIX);
                writer.endControlFlow();
                writer.emitStatement("table.addColumnLink(RealmFieldType.LIST, \"%s\", transaction.getTable(\"%s%s\"))",
                        fieldName, Constants.TABLE_PREFIX, genericType);
            }
        }

        for (VariableElement field : metadata.getIndexedFields()) {
            String fieldName = field.getSimpleName().toString();
            writer.emitStatement("table.addSearchIndex(table.getColumnIndex(\"%s\"))", fieldName);
        }

        if (metadata.hasPrimaryKey()) {
            String fieldName = metadata.getPrimaryKey().getSimpleName().toString();
            writer.emitStatement("table.setPrimaryKey(\"%s\")", fieldName);
        } else {
            writer.emitStatement("table.setPrimaryKey(\"\")");
        }

        writer.emitStatement("return table");
        writer.endControlFlow();
        writer.emitStatement("return transaction.getTable(\"%s%s\")", Constants.TABLE_PREFIX, this.className);
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitValidateTableMethod(JavaWriter writer) throws IOException {
        writer.beginMethod(
                columnInfoClassName(), // Return type
                "validateTable", // Method name
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), // Modifiers
                "ImplicitTransaction", "transaction"); // Argument type & argument name

        writer.beginControlFlow("if (transaction.hasTable(\"" + Constants.TABLE_PREFIX + this.className + "\"))");
        writer.emitStatement("Table table = transaction.getTable(\"%s%s\")", Constants.TABLE_PREFIX, this.className);

        // verify number of columns
        writer.beginControlFlow("if (table.getColumnCount() != " + metadata.getFields().size() + ")");
        writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Field count does not match - expected %d but was \" + table.getColumnCount())",
                metadata.getFields().size());
        writer.endControlFlow();

        // create type dictionary for lookup
        writer.emitStatement("Map<String, RealmFieldType> columnTypes = new HashMap<String, RealmFieldType>()");
        writer.beginControlFlow("for (long i = 0; i < " + metadata.getFields().size() + "; i++)");
        writer.emitStatement("columnTypes.put(table.getColumnName(i), table.getColumnType(i))");
        writer.endControlFlow();
        writer.emitEmptyLine();

        // create an instance of ColumnInfo
        writer.emitStatement("final %1$s columnInfo = new %1$s(transaction.getPath(), table)", columnInfoClassName());
        writer.emitEmptyLine();

        // For each field verify there is a corresponding
        long fieldIndex = 0;
        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String fieldTypeCanonicalName = field.asType().toString();
            String fieldTypeSimpleName = Utils.getFieldTypeSimpleName(field);

            if (Constants.JAVA_TO_REALM_TYPES.containsKey(fieldTypeCanonicalName)) {
                // make sure types align
                writer.beginControlFlow("if (!columnTypes.containsKey(\"%s\"))", fieldName);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Missing field '%s' in existing Realm file. " +
                        "Either remove field or migrate using io.realm.internal.Table.addColumn()." +
                        "\")", fieldName);
                writer.endControlFlow();
                writer.beginControlFlow("if (columnTypes.get(\"%s\") != %s)",
                        fieldName, Constants.JAVA_TO_COLUMN_TYPES.get(fieldTypeCanonicalName));
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Invalid type '%s' for field '%s' in existing Realm file.\")",
                        fieldTypeSimpleName, fieldName);
                writer.endControlFlow();

                // make sure that nullability matches
                if (metadata.isNullable(field)) {
                    writer.beginControlFlow("if (!table.isColumnNullable(%s))", fieldIndexVariableReference(field));
                    if (Utils.isBoxedType(fieldTypeCanonicalName)) {
                        writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath()," +
                                "\"Field '%s' does not support null values in the existing Realm file. " +
                                "Either set @Required, use the primitive type for field '%s' " +
                                "or migrate using io.realm.internal.Table.convertColumnToNullable()." +
                                "\")",
                                fieldName, fieldName);
                    } else {
                        writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath()," +
                                " \"Field '%s' is required. Either set @Required to field '%s' " +
                                "or migrate using io.realm.internal.Table.convertColumnToNullable()." +
                                "\")",
                                fieldName, fieldName);
                    }
                    writer.endControlFlow();
                } else {
                    writer.beginControlFlow("if (table.isColumnNullable(%s))", fieldIndexVariableReference(field));
                    if (Utils.isPrimitiveType(fieldTypeCanonicalName)) {
                        writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath()," +
                                " \"Field '%s' does support null values in the existing Realm file. " +
                                "Use corresponding boxed type for field '%s' or migrate using io.realm.internal.Table.convertColumnToNotNullable().\")",
                                fieldName, fieldName);
                    } else {
                        writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath()," +
                                " \"Field '%s' does support null values in the existing Realm file. " +
                                "Remove @Required or @PrimaryKey from field '%s' or migrate using io.realm.internal.Table.convertColumnToNotNullable().\")",
                                fieldName, fieldName);
                    }
                    writer.endControlFlow();
                }

                // Validate @PrimaryKey
                if (field.equals(metadata.getPrimaryKey())) {
                    writer.beginControlFlow("if (table.getPrimaryKey() != table.getColumnIndex(\"%s\"))", fieldName);
                    writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Primary key not defined for field '%s' in existing Realm file. Add @PrimaryKey.\")", fieldName);
                    writer.endControlFlow();
                }

                // Validate @Index
                if (metadata.getIndexedFields().contains(field)) {
                    writer.beginControlFlow("if (!table.hasSearchIndex(table.getColumnIndex(\"%s\")))", fieldName);
                    writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Index not defined for field '%s' in existing Realm file. " +
                            "Either set @Index or migrate using io.realm.internal.Table.removeSearchIndex().\")", fieldName);
                    writer.endControlFlow();
                }

            } else if (Utils.isRealmObject(field)) { // Links
                writer.beginControlFlow("if (!columnTypes.containsKey(\"%s\"))", fieldName);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Missing field '%s' in existing Realm file. " +
                        "Either remove field or migrate using io.realm.internal.Table.addColumn().\")", fieldName);
                writer.endControlFlow();
                writer.beginControlFlow("if (columnTypes.get(\"%s\") != RealmFieldType.OBJECT)", fieldName);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Invalid type '%s' for field '%s'\")",
                        fieldTypeSimpleName, fieldName);
                writer.endControlFlow();
                writer.beginControlFlow("if (!transaction.hasTable(\"%s%s\"))", Constants.TABLE_PREFIX, fieldTypeSimpleName);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Missing class '%s%s' for field '%s'\")",
                        Constants.TABLE_PREFIX, fieldTypeSimpleName, fieldName);
                writer.endControlFlow();

                writer.emitStatement("Table table_%d = transaction.getTable(\"%s%s\")", fieldIndex, Constants.TABLE_PREFIX, fieldTypeSimpleName);
                writer.beginControlFlow("if (!table.getLinkTarget(%s).hasSameSchema(table_%d))",
                        fieldIndexVariableReference(field), fieldIndex);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Invalid RealmObject for field '%s': '\" + table.getLinkTarget(%s).getName() + \"' expected - was '\" + table_%d.getName() + \"'\")",
                        fieldName, fieldIndexVariableReference(field), fieldIndex);
                writer.endControlFlow();
            } else if (Utils.isRealmList(field)) { // Link Lists
                String genericType = Utils.getGenericType(field);
                writer.beginControlFlow("if (!columnTypes.containsKey(\"%s\"))", fieldName);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Missing field '%s'\")", fieldName);
                writer.endControlFlow();
                writer.beginControlFlow("if (columnTypes.get(\"%s\") != RealmFieldType.LIST)", fieldName);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Invalid type '%s' for field '%s'\")",
                        genericType, fieldName);
                writer.endControlFlow();
                writer.beginControlFlow("if (!transaction.hasTable(\"%s%s\"))", Constants.TABLE_PREFIX, genericType);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Missing class '%s%s' for field '%s'\")",
                        Constants.TABLE_PREFIX, genericType, fieldName);
                writer.endControlFlow();

                writer.emitStatement("Table table_%d = transaction.getTable(\"%s%s\")", fieldIndex, Constants.TABLE_PREFIX, genericType);
                writer.beginControlFlow("if (!table.getLinkTarget(%s).hasSameSchema(table_%d))",
                        fieldIndexVariableReference(field), fieldIndex);
                writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"Invalid RealmList type for field '%s': '\" + table.getLinkTarget(%s).getName() + \"' expected - was '\" + table_%d.getName() + \"'\")",
                        fieldName, fieldIndexVariableReference(field), fieldIndex);
                writer.endControlFlow();
            }
            fieldIndex++;
        }

        writer.emitStatement("return %s", "columnInfo");

        writer.nextControlFlow("else");
        writer.emitStatement("throw new RealmMigrationNeededException(transaction.getPath(), \"The %s class is missing from the schema for this Realm.\")", metadata.getSimpleClassName());
        writer.endControlFlow();
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitGetTableNameMethod(JavaWriter writer) throws IOException {
        writer.beginMethod("String", "getTableName", EnumSet.of(Modifier.PUBLIC, Modifier.STATIC));
        writer.emitStatement("return \"%s%s\"", Constants.TABLE_PREFIX, className);
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitGetFieldNamesMethod(JavaWriter writer) throws IOException {
        writer.beginMethod("List<String>", "getFieldNames", EnumSet.of(Modifier.PUBLIC, Modifier.STATIC));
        writer.emitStatement("return FIELD_NAMES");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitCopyOrUpdateMethod(JavaWriter writer) throws IOException {
        writer.beginMethod(
                className, // Return type
                "copyOrUpdate", // Method name
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), // Modifiers
                "Realm", "realm", className, "object", "boolean", "update", "Map<RealmObject,RealmObjectProxy>", "cache" // Argument type & argument name
        );

        // If object is already in the Realm there is nothing to update
        writer
            .beginControlFlow("if (object.realm != null && object.realm.getPath().equals(realm.getPath()))")
                .emitStatement("return object")
            .endControlFlow();

        if (!metadata.hasPrimaryKey()) {
            writer.emitStatement("return copy(realm, object, update, cache)");
        } else {
            writer
                .emitStatement("%s realmObject = null", className)
                .emitStatement("boolean canUpdate = update")
                .beginControlFlow("if (canUpdate)")
                    .emitStatement("Table table = realm.getTable(%s.class)", className)
                    .emitStatement("long pkColumnIndex = table.getPrimaryKey()");
            if (Utils.isString(metadata.getPrimaryKey())) {
                writer.emitStatement("String primaryKey");
            } else {
                writer.emitStatement("long primaryKey");
            }

            if (Utils.isString(metadata.getPrimaryKey())) {
                writer
                    .beginControlFlow("if (object.realm != null)")
                        .emitStatement("primaryKey = ((%sRealmProxy)object).%s()", className, metadata.getPrimaryKeyGetter())
                    .nextControlFlow("else")
                        .emitStatement("Field field = null;")
                        .emitStatement("Class<? extends %s> clazz = object.getClass()", className)
                        .beginControlFlow("try")
                            .emitStatement("field = clazz.getDeclaredField(\"%s\")", metadata.getPrimaryKey())
                        .nextControlFlow("catch (NoSuchFieldException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                        .emitStatement("field.setAccessible(true)")
                        .beginControlFlow("try")
                            .emitStatement("primaryKey = (String) field.get(object)")
                        .nextControlFlow("catch (IllegalAccessException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                    .endControlFlow()
                    .beginControlFlow("if (primaryKey == null)")
                        .emitStatement("throw new IllegalArgumentException(\"Primary key value must not be null.\")")
                    .endControlFlow()
                    .emitStatement("long rowIndex = table.findFirstString(pkColumnIndex, primaryKey)", metadata.getPrimaryKeyGetter());
            } else {
                writer
                    .beginControlFlow("if (object.realm != null)")
                        .emitStatement("primaryKey = ((%sRealmProxy)object).%s()", className, metadata.getPrimaryKeyGetter())
                    .nextControlFlow("else")
                        .emitStatement("Field field = null;")
                        .emitStatement("Class<? extends %s> clazz = object.getClass()", className)
                        .beginControlFlow("try")
                            .emitStatement("field = clazz.getDeclaredField(\"%s\")", metadata.getPrimaryKey())
                        .nextControlFlow("catch (NoSuchFieldException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                        .emitStatement("field.setAccessible(true)")
                        .beginControlFlow("try")
                            .emitStatement("primaryKey = (Long) field.get(object)")
                        .nextControlFlow("catch (IllegalAccessException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                    .endControlFlow()
                    .emitStatement("long rowIndex = table.findFirstLong(pkColumnIndex, object.%s())", metadata.getPrimaryKeyGetter());
            }

            writer
                .beginControlFlow("if (rowIndex != TableOrView.NO_MATCH)")
                    .emitStatement("realmObject = new %s(realm.schema.getColumnInfo(%s.class))",
                            Utils.getProxyClassName(className),
                            className)
                    .emitStatement("realmObject.realm = realm")
                    .emitStatement("realmObject.row = table.getUncheckedRow(rowIndex)")
                    .emitStatement("cache.put(object, (RealmObjectProxy) realmObject)")
                .nextControlFlow("else")
                    .emitStatement("canUpdate = false")
                .endControlFlow();

            writer.endControlFlow();

            writer
                .emitEmptyLine()
                .beginControlFlow("if (canUpdate)")
                    .emitStatement("return update(realm, realmObject, object, cache)")
                .nextControlFlow("else")
                    .emitStatement("return copy(realm, object, update, cache)")
                .endControlFlow();
        }

        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitCopyMethod(JavaWriter writer) throws IOException {
        writer.beginMethod(
                className, // Return type
                "copy", // Method name
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), // Modifiers
                "Realm", "realm", className, "from", "boolean", "update", "Map<RealmObject,RealmObjectProxy>", "cache"); // Argument type & argument name

        writer.emitStatement("final boolean isStandalone = !(from instanceof %sRealmProxy)", className);
        writer.emitStatement("Class<? extends %s> clazz", className);
        writer.emitStatement("Field field = null");
        writer
            .beginControlFlow("if (isStandalone)")
                .emitStatement("clazz = from.getClass()")
            .nextControlFlow("else")
                .emitStatement("clazz = null")
            .endControlFlow()
            .emitStatement("%sRealmProxy to", className);
        writer.emitEmptyLine();

        if (metadata.hasPrimaryKey()) {
            writer
                .beginControlFlow("if (isStandalone)")
                    .beginControlFlow("try")
                        .emitStatement("field = clazz.getDeclaredField(\"%s\")", metadata.getPrimaryKey().getSimpleName().toString())
                    .nextControlFlow("catch (NoSuchFieldException e)")
                        .emitStatement("throw new RealmException(e.getMessage())")
                    .endControlFlow()
                    .emitStatement("field.setAccessible(true)")
                    .emitStatement("Object primaryKey")
                    .beginControlFlow("try")
                        .emitStatement("primaryKey = field.get(from)")
                    .nextControlFlow("catch (IllegalAccessException e)")
                        .emitStatement("throw new RealmException(e.getMessage())")
                    .endControlFlow()
                    .emitStatement("to = (%sRealmProxy) realm.createObject(%s.class, primaryKey)",
                    className, className)
                .nextControlFlow("else")
                    .emitStatement("to = (%sRealmProxy) realm.createObject(%s.class, ((%sRealmProxy) from).%s())",
                    className, className, className, metadata.getPrimaryKeyGetter())
                .endControlFlow();
        } else {
            writer.emitStatement("to = (%sRealmProxy) realm.createObject(%s.class)",
                    className, className);
        }

        writer.emitStatement("cache.put(from, (RealmObjectProxy) to)");
        writer.emitEmptyLine();

        writer.beginControlFlow("try");

        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String fieldType = field.asType().toString();
            String setter = metadata.getSetter(fieldName);
            String getter = metadata.getGetter(fieldName);

            if (Utils.isRealmObject(field)) {
                writer.emitEmptyLine();
                writer.beginControlFlow("");

                writer.emitStatement("%s %sObj", fieldType, fieldName);
                writer
                    .beginControlFlow("if (isStandalone)")
                        .emitStatement("field = clazz.getDeclaredField(\"%s\")", fieldName)
                        .emitStatement("field.setAccessible(true)")
                        .emitStatement("%sObj = (%s) field.get(from)", fieldName, fieldType)
                    .nextControlFlow("else")
                        .emitStatement("%sObj = ((%sRealmProxy) from).%s()", fieldName, className, getter)
                    .endControlFlow();

                writer
                    .beginControlFlow("if (%sObj != null)", fieldName)
                        .emitStatement("%s cache%s = (%s) cache.get(%sObj)", fieldType, fieldName, fieldType, fieldName)
                        .beginControlFlow("if (cache%s != null)", fieldName)
                            .emitStatement("to.%s(cache%s)", setter, fieldName)
                        .nextControlFlow("else")
                            .emitStatement("to.%s(%s.copyOrUpdate(realm, %sObj, update, cache))",
                                metadata.getSetter(fieldName), Utils.getProxyClassSimpleName(field), fieldName)
                        .endControlFlow()
                    .nextControlFlow("else")
                        // No need to throw exception here if the field is not nullable. A exception will be thrown in setter.
                        .emitStatement("to.%s(null)", setter)
                    .endControlFlow();

                writer.endControlFlow();
            } else if (Utils.isRealmList(field)) {
                writer.emitEmptyLine();
                writer.beginControlFlow("");

                writer.emitStatement("RealmList<%s> %sList", Utils.getGenericType(field), fieldName);
                writer
                    .beginControlFlow("if (isStandalone)")
                        .emitStatement("field = clazz.getDeclaredField(\"%s\")", fieldName)
                        .emitStatement("field.setAccessible(true)")
                        .emitStatement("%sList = (RealmList<%s>) field.get(from)",
                                fieldName, Utils.getGenericType(field))
                    .nextControlFlow("else")
                        .emitStatement("%sList = ((%sRealmProxy) from).%s()", fieldName, className, getter)
                    .endControlFlow();

                writer
                    .beginControlFlow("if (%sList != null)", fieldName)
                        .emitStatement("RealmList<%s> %sRealmList = to.%s()", Utils.getGenericType(field), fieldName, getter)
                        .beginControlFlow("for (int i = 0; i < %sList.size(); i++)", fieldName)
                            .emitStatement("%s %sItem = %sList.get(i)", Utils.getGenericType(field), fieldName, fieldName)
                            .emitStatement("%s cache%s = (%s) cache.get(%sItem)", Utils.getGenericType(field), fieldName, Utils.getGenericType(field), fieldName)
                            .beginControlFlow("if (cache%s != null)", fieldName)
                                .emitStatement("%sRealmList.add(cache%s)", fieldName, fieldName)
                            .nextControlFlow("else")
                                .emitStatement("%sRealmList.add(%s.copyOrUpdate(realm, %sList.get(i), update, cache))", fieldName, Utils.getProxyClassSimpleName(field), fieldName)
                            .endControlFlow()
                        .endControlFlow()
                    .endControlFlow();

                writer.endControlFlow();
            } else {
                writer
                    .beginControlFlow("if (isStandalone)")
                        .emitStatement("field = clazz.getDeclaredField(\"%s\")", fieldName)
                        .emitStatement("field.setAccessible(true)")
                        .emitStatement("to.%s((%s) field.get(from))", metadata.getSetter(fieldName), fieldType)
                    .nextControlFlow("else")
                        .emitStatement("to.%s(((%sRealmProxy) from).%s())", metadata.getSetter(fieldName), className, getter)
                    .endControlFlow();
            }
        }

        writer
            .nextControlFlow("catch (NoSuchFieldException e)")
                .emitStatement("throw new RealmException(e.getMessage())")
            .nextControlFlow("catch (IllegalAccessException e)")
                .emitStatement("throw new RealmException(e.getMessage())")
            .endControlFlow();

        writer.emitStatement("return to");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitCreateDetachedCopyMethod(JavaWriter writer) throws IOException {
        writer.beginMethod(
                className, // Return type
                "createDetachedCopy", // Method name
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC), // Modifiers
                className, "realmObject", "int", "currentDepth", "int", "maxDepth", "Map<RealmObject, CacheData<RealmObject>>", "cache");
        writer
            .beginControlFlow("if (currentDepth > maxDepth || realmObject == null)")
                .emitStatement("return null")
            .endControlFlow()
            .emitStatement("CacheData<%s> cachedObject = (CacheData) cache.get(realmObject)", className)
            .emitStatement("%s standaloneObject", className)
            .beginControlFlow("if (cachedObject != null)")
                .emitSingleLineComment("Reuse cached object or recreate it because it was encountered at a lower depth.")
                .beginControlFlow("if (currentDepth >= cachedObject.minDepth)")
                    .emitStatement("return cachedObject.object")
                .nextControlFlow("else")
                    .emitStatement("standaloneObject = cachedObject.object")
                    .emitStatement("cachedObject.minDepth = currentDepth")
                .endControlFlow()
            .nextControlFlow("else")
                .emitStatement("standaloneObject = new %s()", className)
                .emitStatement("cache.put(realmObject, new RealmObjectProxy.CacheData<RealmObject>(currentDepth, standaloneObject))")
            .endControlFlow();

        writer.emitStatement("Class<?> clazz = standaloneObject.getClass()");
        writer.emitStatement("Field field = null");

        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String getter = metadata.getGetter(fieldName);

            writer.beginControlFlow("try");
            writer.emitStatement("field = clazz.getDeclaredField(\"%s\")", fieldName);
            writer.nextControlFlow("catch (NoSuchFieldException e)");
            writer.emitStatement("throw new RealmException(e.getMessage())");
            writer.endControlFlow();
            writer.emitStatement("field.setAccessible(true)");

            if (Utils.isRealmObject(field)) {
                writer
                    .emitEmptyLine()
                    .emitSingleLineComment("Deep copy of %s", fieldName)
                    .beginControlFlow("try")
                        .emitStatement("field.set(standaloneObject, %s.createDetachedCopy( ((%sRealmProxy) " +
                                "realmObject).%s(), currentDepth + 1, maxDepth, cache))",
                            Utils.getProxyClassSimpleName(field), className, getter)
                    .nextControlFlow("catch (IllegalAccessException e)")
                        .emitStatement("throw new RealmException(e.getMessage())")
                    .endControlFlow();
            } else if (Utils.isRealmList(field)) {
                writer
                    .emitEmptyLine()
                    .emitSingleLineComment("Deep copy of %s", fieldName)
                    .beginControlFlow("if (currentDepth == maxDepth)")
                        .beginControlFlow("try")
                            .emitStatement("field.set(standaloneObject, null)")
                        .nextControlFlow("catch (IllegalAccessException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                    .nextControlFlow("else")
                        .emitStatement("RealmList<%s> managed%sList = ((%sRealmProxy) realmObject).%s()",
                                Utils.getGenericType(field), fieldName, className, getter)
                        .emitStatement("RealmList<%1$s> standalone%2$sList = new RealmList<%1$s>()", Utils.getGenericType(field), fieldName)
                        .beginControlFlow("try")
                            .emitStatement("field.set(standaloneObject, standalone%sList)", fieldName)
                        .nextControlFlow("catch (IllegalAccessException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                        .emitStatement("int nextDepth = currentDepth + 1")
                        .emitStatement("int size = managed%sList.size()", fieldName)
                        .beginControlFlow("for (int i = 0; i < size; i++)")
                            .emitStatement("%s item = %s.createDetachedCopy(managed%sList.get(i), nextDepth, maxDepth, cache)",
                                    Utils.getGenericType(field), Utils.getProxyClassSimpleName(field), fieldName)
                            .emitStatement("standalone%sList.add(item)", fieldName)
                        .endControlFlow()
                    .endControlFlow();
            } else {
                writer.beginControlFlow("try");
                writer.emitStatement("field.%s(standaloneObject, ((%sRealmProxy) realmObject).%s())",
                        Constants.JAVA_TO_FIELD_SETTER.get(field.asType().toString()), className, getter);
                writer.nextControlFlow("catch (IllegalAccessException e)");
                writer.emitStatement("throw new RealmException(e.getMessage())");
                writer.endControlFlow();

            }
        }

        writer.emitStatement("return standaloneObject");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitUpdateMethod(JavaWriter writer) throws IOException {
        if (!metadata.hasPrimaryKey()) {
            return;
        }

        writer.beginMethod(
                className, // Return type
                "update", // Method name
                EnumSet.of(Modifier.STATIC), // Modifiers
                "Realm", "realm", className, "realmObject", className, "newObject", "Map<RealmObject, RealmObjectProxy>", "cache"); // Argument type & argument name

        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String setter = metadata.getSetter(fieldName);
            String getter = metadata.getGetter(fieldName);
            if (Utils.isRealmObject(field)) {
                writer
                    .emitStatement("%s %sObj = newObject.%s()", Utils.getFieldTypeSimpleName(field), fieldName, getter)
                    .beginControlFlow("if (%sObj != null)", fieldName)
                        .emitStatement("%s cache%s = (%s) cache.get(%sObj)", Utils.getFieldTypeSimpleName(field), fieldName, Utils.getFieldTypeSimpleName(field), fieldName)
                        .beginControlFlow("if (cache%s != null)", fieldName)
                            .emitStatement("((%sRealmProxy) realmObject).%s(cache%s)", className, setter, fieldName)
                        .nextControlFlow("else")
                            .emitStatement("((%sRealmProxy) realmObject).%s(%s.copyOrUpdate(realm, %sObj, true, cache))",
                                    className,
                                    setter,
                                    Utils.getProxyClassSimpleName(field),
                                    fieldName,
                                    Utils.getFieldTypeSimpleName(field)
                            )
                        .endControlFlow()
                    .nextControlFlow("else")
                        // No need to throw exception here if the field is not nullable. A exception will be thrown in setter.
                        .emitStatement("((%sRealmProxy) realmObject).%s(null)", className, setter)
                    .endControlFlow();
            } else if (Utils.isRealmList(field)) {
                writer
                    .emitStatement("RealmList<%s> %sList = newObject.%s()", Utils.getGenericType(field), fieldName, getter)
                    .emitStatement("RealmList<%s> %sRealmList = realmObject.%s()", Utils.getGenericType(field), fieldName, getter)
                    .emitStatement("%sRealmList.clear()", fieldName)
                    .beginControlFlow("if (%sList != null)", fieldName)
                        .beginControlFlow("for (int i = 0; i < %sList.size(); i++)", fieldName)
                            .emitStatement("%s %sItem = %sList.get(i)", Utils.getGenericType(field), fieldName, fieldName)
                            .emitStatement("%s cache%s = (%s) cache.get(%sItem)", Utils.getGenericType(field), fieldName, Utils.getGenericType(field), fieldName)
                            .beginControlFlow("if (cache%s != null)", fieldName)
                                .emitStatement("%sRealmList.add(cache%s)", fieldName, fieldName)
                            .nextControlFlow("else")
                                .emitStatement("%sRealmList.add(%s.copyOrUpdate(realm, %sList.get(i), true, cache))", fieldName, Utils.getProxyClassSimpleName(field), fieldName)
                            .endControlFlow()
                        .endControlFlow()
                    .endControlFlow();

            } else {
                if (field == metadata.getPrimaryKey()) {
                    continue;
                }
                writer
                    .beginControlFlow("if (newObject.realm == null)")
                        .emitStatement("Field field")
                        .emitStatement("Class<? extends %s> clazz = newObject.getClass()", className)
                        .beginControlFlow("try")
                            .emitStatement("field = clazz.getDeclaredField(\"%s\")", fieldName)
                        .nextControlFlow("catch (NoSuchFieldException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                        .emitStatement("field.setAccessible(true)")
                        .beginControlFlow("try")
                            .emitStatement("((%sRealmProxy) realmObject).%s(((%s) field.get(newObject)))", className, setter, Utils.getFieldTypeSimpleName(field))
                        .nextControlFlow("catch (IllegalAccessException e)")
                            .emitStatement("throw new RealmException(e.getMessage())")
                        .endControlFlow()
                    .nextControlFlow("else")
                        .emitStatement("((%sRealmProxy) realmObject).%s(((%sRealmProxy) newObject).%s())", className, setter, className, getter)
                    .endControlFlow();
            }
        }

        writer.emitStatement("return realmObject");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitToStringMethod(JavaWriter writer) throws IOException {
        writer.emitAnnotation("Override");
        writer.beginMethod("String", "toString", EnumSet.of(Modifier.PUBLIC));
        writer.beginControlFlow("if (!isValid())");
        writer.emitStatement("return \"Invalid object\"");
        writer.endControlFlow();
        writer.emitStatement("StringBuilder stringBuilder = new StringBuilder(\"%s = [\")", className);
        List<VariableElement> fields = metadata.getFields();
        for (int i = 0; i < fields.size(); i++) {
            VariableElement field = fields.get(i);
            String fieldName = field.getSimpleName().toString();

            writer.emitStatement("stringBuilder.append(\"{%s:\")", fieldName);
            if (Utils.isRealmObject(field)) {
                String fieldTypeSimpleName = Utils.getFieldTypeSimpleName(field);
                writer.emitStatement(
                        "stringBuilder.append(%s() != null ? \"%s\" : \"null\")",
                        metadata.getGetter(fieldName),
                        fieldTypeSimpleName
                );
            } else if (Utils.isRealmList(field)) {
                String genericType = Utils.getGenericType(field);
                writer.emitStatement("stringBuilder.append(\"RealmList<%s>[\").append(%s().size()).append(\"]\")",
                        genericType,
                        metadata.getGetter(fieldName));
            } else {
                if (metadata.isNullable(field)) {
                    writer.emitStatement("stringBuilder.append(%s() != null ? %s() : \"null\")",
                            metadata.getGetter(fieldName),
                            metadata.getGetter(fieldName)
                    );
                } else {
                    writer.emitStatement("stringBuilder.append(%s())", metadata.getGetter(fieldName));
                }
            }
            writer.emitStatement("stringBuilder.append(\"}\")");

            if (i < fields.size() - 1) {
                writer.emitStatement("stringBuilder.append(\",\")");
            }
        }

        writer.emitStatement("stringBuilder.append(\"]\")");
        writer.emitStatement("return stringBuilder.toString()");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitHashcodeMethod(JavaWriter writer) throws IOException {
        writer.emitAnnotation("Override");
        writer.beginMethod("int", "hashCode", EnumSet.of(Modifier.PUBLIC));
        writer.emitStatement("String realmName = realm.getPath()");
        writer.emitStatement("String tableName = row.getTable().getName()");
        writer.emitStatement("long rowIndex = row.getIndex()");
        writer.emitEmptyLine();
        writer.emitStatement("int result = 17");
        writer.emitStatement("result = 31 * result + ((realmName != null) ? realmName.hashCode() : 0)");
        writer.emitStatement("result = 31 * result + ((tableName != null) ? tableName.hashCode() : 0)");
        writer.emitStatement("result = 31 * result + (int) (rowIndex ^ (rowIndex >>> 32))");
        writer.emitStatement("return result");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitEqualsMethod(JavaWriter writer) throws IOException {
        String proxyClassName = className + Constants.PROXY_SUFFIX;
        writer.emitAnnotation("Override");
        writer.beginMethod("boolean", "equals", EnumSet.of(Modifier.PUBLIC), "Object", "o");
        writer.emitStatement("if (this == o) return true");
        writer.emitStatement("if (o == null || getClass() != o.getClass()) return false");
        writer.emitStatement("%s a%s = (%s)o", proxyClassName, className, proxyClassName);  // FooRealmProxy aFoo = (FooRealmProxy)o
        writer.emitEmptyLine();
        writer.emitStatement("String path = realm.getPath()");
        writer.emitStatement("String otherPath = a%s.realm.getPath()", className);
        writer.emitStatement("if (path != null ? !path.equals(otherPath) : otherPath != null) return false;");
        writer.emitEmptyLine();
        writer.emitStatement("String tableName = row.getTable().getName()");
        writer.emitStatement("String otherTableName = a%s.row.getTable().getName()", className);
        writer.emitStatement("if (tableName != null ? !tableName.equals(otherTableName) : otherTableName != null) return false");
        writer.emitEmptyLine();
        writer.emitStatement("if (row.getIndex() != a%s.row.getIndex()) return false", className);
        writer.emitEmptyLine();
        writer.emitStatement("return true");
        writer.endMethod();
        writer.emitEmptyLine();
    }


    private void emitCreateOrUpdateUsingJsonObject(JavaWriter writer) throws IOException {
        writer.emitAnnotation("SuppressWarnings", "\"cast\"");
        writer.beginMethod(
                className,
                "createOrUpdateUsingJsonObject",
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC),
                Arrays.asList("Realm", "realm", "JSONObject", "json", "boolean", "update"),
                Collections.singletonList("JSONException"));

        if (!metadata.hasPrimaryKey()) {
            writer.emitStatement("%sRealmProxy obj = (%sRealmProxy) realm.createObject(%s.class)", className,
                    className, className);
        } else {
            String pkType = Utils.isString(metadata.getPrimaryKey()) ? "String" : "Long";
            writer
                .emitStatement("%sRealmProxy obj = null", className)
                .beginControlFlow("if (update)")
                    .emitStatement("Table table = realm.getTable(%s.class)", className)
                    .emitStatement("long pkColumnIndex = table.getPrimaryKey()")
                    .beginControlFlow("if (!json.isNull(\"%s\"))", metadata.getPrimaryKey().getSimpleName())
                    .emitStatement("long rowIndex = table.findFirst%s(pkColumnIndex, json.get%s(\"%s\"))",
                            pkType, pkType, metadata.getPrimaryKey().getSimpleName())
                    .beginControlFlow("if (rowIndex != TableOrView.NO_MATCH)")
                            .emitStatement("obj = new %s(realm.schema.getColumnInfo(%s.class))",
                                    Utils.getProxyClassName(className),
                                    className)
                            .emitStatement("obj.realm = realm")
                            .emitStatement("obj.row = table.getUncheckedRow(rowIndex)")
                        .endControlFlow()
                    .endControlFlow()
                .endControlFlow();

            writer.beginControlFlow("if (obj == null)");
            String primaryKeyFieldType = metadata.getPrimaryKey().asType().toString();
            String primaryKeyFieldName = metadata.getPrimaryKey().getSimpleName().toString();
            RealmJsonTypeHelper.emitCreateObjectWithPrimaryKeyValue(className, primaryKeyFieldType, primaryKeyFieldName, writer);
            writer.endControlFlow();
        }

        for (VariableElement field : metadata.getFields()) {
            String fieldName = field.getSimpleName().toString();
            String qualifiedFieldType = field.asType().toString();
            if (Utils.isRealmObject(field)) {
                RealmJsonTypeHelper.emitFillRealmObjectWithJsonValue(
                        metadata.getSetter(fieldName),
                        fieldName,
                        qualifiedFieldType,
                        Utils.getProxyClassSimpleName(field),
                        writer
                );

            } else if (Utils.isRealmList(field)) {
                RealmJsonTypeHelper.emitFillRealmListWithJsonValue(
                        metadata.getGetter(fieldName),
                        metadata.getSetter(fieldName),
                        fieldName,
                        ((DeclaredType) field.asType()).getTypeArguments().get(0).toString(),
                        Utils.getProxyClassSimpleName(field),
                        writer);

            } else {
                RealmJsonTypeHelper.emitFillJavaTypeWithJsonValue(
                        metadata.getSetter(fieldName),
                        fieldName,
                        qualifiedFieldType,
                        writer
                );
            }
        }

        writer.emitStatement("return obj");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private void emitCreateUsingJsonStream(JavaWriter writer) throws IOException {
        writer.emitAnnotation("SuppressWarnings", "\"cast\"");
        writer.beginMethod(
                className,
                "createUsingJsonStream",
                EnumSet.of(Modifier.PUBLIC, Modifier.STATIC),
                Arrays.asList("Realm", "realm", "JsonReader", "reader"),
                Collections.singletonList("IOException"));

        writer.emitStatement("%sRealmProxy obj = (%sRealmProxy) realm.createObject(%s.class)",className, className,
                className);
        writer.emitStatement("reader.beginObject()");
        writer.beginControlFlow("while (reader.hasNext())");
        writer.emitStatement("String name = reader.nextName()");

        List<VariableElement> fields = metadata.getFields();
        for (int i = 0; i < fields.size(); i++) {
            VariableElement field = fields.get(i);
            String fieldName = field.getSimpleName().toString();
            String qualifiedFieldType = field.asType().toString();

            if (i == 0) {
                writer.beginControlFlow("if (name.equals(\"%s\"))", fieldName);
            } else {
                writer.nextControlFlow("else if (name.equals(\"%s\"))", fieldName);
            }
            if (Utils.isRealmObject(field)) {
                RealmJsonTypeHelper.emitFillRealmObjectFromStream(
                        metadata.getSetter(fieldName),
                        fieldName,
                        qualifiedFieldType,
                        Utils.getProxyClassSimpleName(field),
                        writer
                );

            } else if (Utils.isRealmList(field)) {
                RealmJsonTypeHelper.emitFillRealmListFromStream(
                        metadata.getGetter(fieldName),
                        metadata.getSetter(fieldName),
                        ((DeclaredType) field.asType()).getTypeArguments().get(0).toString(),
                        Utils.getProxyClassSimpleName(field),
                        writer);

            } else {
                RealmJsonTypeHelper.emitFillJavaTypeFromStream(
                        metadata.getSetter(fieldName),
                        fieldName,
                        qualifiedFieldType,
                        writer
                );
            }
        }

        if (fields.size() > 0) {
            writer.nextControlFlow("else");
            writer.emitStatement("reader.skipValue()");
            writer.endControlFlow();
        }
        writer.endControlFlow();
        writer.emitStatement("reader.endObject()");
        writer.emitStatement("return obj");
        writer.endMethod();
        writer.emitEmptyLine();
    }

    private String columnInfoClassName() {
        return className + "ColumnInfo";
    }

    private String columnIndexVarName(VariableElement variableElement) {
        return variableElement.getSimpleName().toString() + "Index";
    }

    private String fieldIndexVariableReference(VariableElement variableElement) {
        return "columnInfo." + columnIndexVarName(variableElement);
    }
}

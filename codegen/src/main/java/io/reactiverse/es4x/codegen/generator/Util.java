/*
 * Copyright 2018 Paulo Lopes.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.reactiverse.es4x.codegen.generator;

import io.vertx.codegen.ModuleInfo;
import io.vertx.codegen.TypeParamInfo;
import io.vertx.codegen.doc.Doc;
import io.vertx.codegen.doc.Tag;
import io.vertx.codegen.doc.Token;
import io.vertx.codegen.type.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static io.vertx.codegen.type.ClassKind.DATA_OBJECT;
import static io.vertx.codegen.type.ClassKind.ENUM;
import static javax.lang.model.element.ElementKind.*;

public final class Util {

  private Util() {
    throw new RuntimeException("Static Class");
  }

  private final static JsonArray REGISTRY;
  private final static int YEAR;

  private final static Map<String, String> TYPES = new HashMap<>();

  private final static Set<String> RESERVED = new HashSet<>();

  private final static Map<String, JsonObject> OVERRIDES = new HashMap<>();
  private final static JsonArray OPTIONAL_DEPENDENCIES;
  private final static JsonArray CLASS_BLACKLIST;

  static {
    /* parse the registry from the system property */
    REGISTRY = new JsonArray(System.getProperty("scope-registry", "[]"));
    YEAR = Calendar.getInstance().get(Calendar.YEAR);
    OPTIONAL_DEPENDENCIES = new JsonArray(System.getProperty("npm-optional-dependencies", "[]"));
    CLASS_BLACKLIST = new JsonArray(System.getProperty("npm-class-blacklist", "[]"));

    // register known java <-> js types
    TYPES.put("io.vertx.core.Closeable", "(completionHandler: ((res: AsyncResult<void>) => void) | Handler<AsyncResult<void>>) => void");
    TYPES.put("java.lang.CharSequence", "string");
    TYPES.put("java.lang.Iterable<java.lang.String>", "string[]");
    TYPES.put("java.lang.Iterable<java.lang.CharSequence>", "string[]");
    TYPES.put("java.lang.Boolean[]", "boolean[]");
    TYPES.put("java.lang.Double[]", "number[]");
    TYPES.put("java.lang.Float[]", "number[]");
    TYPES.put("java.lang.Integer[]", "number[]");
    TYPES.put("java.lang.Long[]", "number[]");
    TYPES.put("java.lang.Short[]", "number[]");
    TYPES.put("java.lang.String[]", "string[]");

    TYPES.put("java.time.Instant", "Date");
    TYPES.put("java.time.LocalDate", "Date");
//    TYPES.put("java.time.LocalTime", "Date");
    TYPES.put("java.time.LocalDateTime", "Date");
    TYPES.put("java.time.ZonedDateTime", "Date");
//    TYPES.put("java.time.ZoneId", "Date");
//    TYPES.put("java.time.Duration", "Date");

    // reserved typescript keywords
    RESERVED.addAll(Arrays.asList(
      "break",
      "case",
      "catch",
      "class",
      "const",
      "continue",
      "debugger",
      "default",
      "delete",
      "do",
      "else",
      "enum",
      "export",
      "extends",
      "false",
      "finally",
      "for",
      "function",
      "if",
      "import",
      "in",
      "instanceof",
      "new",
      "null",
      "return",
      "super",
      "switch",
      "this",
      "throw",
      "true",
      "try",
      "typeof",
      "var",
      "void",
      "while",
      "with",
      // strict mode reserved words
      "as",
      "implements",
      "interface",
      "let",
      "package",
      "private",
      "protected",
      "public",
      "static",
      "yield"
    ));
  }

  public static boolean isOptionalModule(String name) {
    return OPTIONAL_DEPENDENCIES.contains(name);
  }

  public static boolean isBlacklistedClass(String name) {
    return CLASS_BLACKLIST.contains(name);
  }

  public static String genType(TypeInfo type) {

    switch (type.getKind()) {
      case STRING:
        return "string";
      case BOXED_PRIMITIVE:
      case PRIMITIVE:
        switch (type.getSimpleName()) {
          case "boolean":
          case "Boolean":
            return "boolean";
          case "char":
          case "Character":
            return "string";
          default:
            return "number";
        }
      case ENUM:
        if (type.getRaw().getModule() != null) {
          return type.getSimpleName();
        } else {
          return "any";
        }
      case OBJECT:
        if (type.isVariable()) {
          return type.getName();
        } else {
          return "any";
        }
      case JSON_OBJECT:
        return "{ [key: string]: any }";
      case JSON_ARRAY:
        return "any[]";
      case THROWABLE:
        return "Error";
      case VOID:
        return "void";
      case LIST:
      case SET:
        if (type.isParameterized()) {
          return genType(((ParameterizedTypeInfo) type).getArg(0)) + "[]";
        } else {
          return "any[]";
        }
      case MAP:
        if (type.isParameterized()) {
          return "{ [key: " + genType(((ParameterizedTypeInfo) type).getArg(0)) + "]: " + genType(((ParameterizedTypeInfo) type).getArg(1)) + "; }";
        } else {
          return "{ [key: string]: any }";
        }
      case API:
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (type.isParameterized()) {
          for (TypeInfo t : ((ParameterizedTypeInfo) type).getArgs()) {
            if (!first) {
              sb.append(", ");
            }
            sb.append(genType(t));
            first = false;
          }
          if (TYPES.containsKey(type.getRaw().getName())) {
            return TYPES.get(type.getRaw().getName()) + "<" + sb.toString() + ">";
          } else {
            return type.getRaw().getSimpleName() + "<" + sb.toString() + ">";
          }
        } else {
          // TS is strict with generics, you can't define/use a generic type without its generic <T>
          if (type.getRaw() != null && type.getRaw().getParams().size() > 0) {
            for (TypeParamInfo t : type.getRaw().getParams()) {
              if (!first) {
                sb.append(", ");
              }
              sb.append("any");
              first = false;
            }
            if (TYPES.containsKey(type.getName())) {
              return TYPES.get(type.getName()) + "<" + sb.toString() + ">";
            } else {
              return type.getSimpleName() + "<" + sb.toString() + ">";
            }
          } else {
            if (TYPES.containsKey(type.getErased().getName())) {
              return TYPES.get(type.getErased().getName());
            } else {
              return type.getErased().getSimpleName();
            }
          }
        }
      case DATA_OBJECT:
        return type.getErased().getSimpleName();
      case HANDLER:
        if (type.isParameterized()) {
          return "((res: " + genType(((ParameterizedTypeInfo) type).getArg(0)) + ") => void) | Handler<" + genType(((ParameterizedTypeInfo) type).getArg(0)) + ">";
        } else {
          return "((res: any) => void) | Handler<any>";
        }
      case FUNCTION:
        if (type.isParameterized()) {
          return "(arg: " + genType(((ParameterizedTypeInfo) type).getArg(0)) + ") => " + genType(((ParameterizedTypeInfo) type).getArg(1));
        } else {
          return "(arg: any) => any";
        }
      case ASYNC_RESULT:
        if (type.isParameterized()) {
          return "AsyncResult<" + genType(((ParameterizedTypeInfo) type).getArg(0)) + ">";
        } else {
          return "AsyncResult<any>";
        }
      case CLASS_TYPE:
        return "any /* TODO: class */";
      case OTHER:
        if (TYPES.containsKey(type.getName())) {
          return TYPES.get(type.getName());
        } else {
          System.out.println("@@@ " + type.getName());
          return "any /* " + type.getName() + " */";
        }
      default:
        System.out.println("!!! " + type + " - " + type.getKind());
        return "";
    }
  }

  public static String genGeneric(List<? extends TypeParamInfo> params) {
    StringBuilder sb = new StringBuilder();

    if (params.size() > 0) {
      sb.append("<");
      boolean firstParam = true;
      for (TypeParamInfo p : params) {
        if (!firstParam) {
          sb.append(", ");
        }
        sb.append(p.getName());
        firstParam = false;
      }
      sb.append(">");
    }

    return sb.toString();
  }

  public static boolean isImported(TypeInfo ref, Map<String, Object> session) {
    if (ref.getRaw().getModuleName() == null) {
      return true;
    }

    final String key = ref.getRaw().getModuleName() + "/" + ref.getSimpleName();

    if (!session.containsKey(key)) {
      session.put(key, ref);
      return false;
    }

    return true;
  }

  public static String getNPMScope(ModuleInfo module) {

    String scope = "";
    String name = "";

    /* get from REGISTRY */
    for (Object el : REGISTRY) {
      JsonObject entry = (JsonObject) el;

      if (entry.getString("group").equals(module.getGroupPackage())) {
        scope = entry.getString("scope", "");
        if (scope.charAt(0) != '@') {
          scope = "@" + scope;
        }
        if (scope.charAt(scope.length() - 1) != '/') {
          scope += "/";
        }
        if (entry.containsKey("prefix")) {
          if (module.getName().startsWith(entry.getString("prefix"))) {
            if (entry.getBoolean("stripPrefix")) {
              name = module.getName().substring(entry.getString("prefix").length());
            } else {
              name = module.getName();
            }
          }
        }
        if (entry.containsKey("module")) {
          if (module.getName().equals(entry.getString("module"))) {
            name = entry.getString("name");
          }
        }
      }
    }

    if (name.equals("")) {
      name = module.getName();
    }

    return scope + name;
  }

  public static String includeFileIfPresent(String file) {
    final File path = new File(System.getProperty("basedir"), file);
    if (path.exists()) {
      try {
        byte[] bytes = Files.readAllBytes(path.toPath());
        String md = new String(bytes, StandardCharsets.UTF_8);
        if (md.length() > 0) {
          if (md.charAt(md.length() - 1) != '\n') {
            return md + "\n\n";
          } else {
            return md + "\n";
          }
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    return "";
  }

  public static void generateLicense(PrintWriter writer) {
    writer.println("/*");
    writer.println(" * Copyright " + YEAR + " ES4X");
    writer.println(" *");
    writer.println(" * ES4X licenses this file to you under the Apache License, version 2.0");
    writer.println(" * (the \"License\"); you may not use this file except in compliance with the");
    writer.println(" * License.  You may obtain a copy of the License at:");
    writer.println(" *");
    writer.println(" * http://www.apache.org/licenses/LICENSE-2.0");
    writer.println(" *");
    writer.println(" * Unless required by applicable law or agreed to in writing, software");
    writer.println(" * distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT");
    writer.println(" * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the");
    writer.println(" * License for the specific language governing permissions and limitations");
    writer.println(" * under the License.");
    writer.println(" */");
    writer.println();
  }

  public static String cleanReserved(String value) {
    if (RESERVED.contains(value)) {
      return "__" + value;
    }
    return value;
  }

  private static JsonObject getOverride(String type) {
    JsonObject overrides = OVERRIDES.get(type);

    if (overrides == null) {
      String raw = includeFileIfPresent(type + ".override.json");
      if (raw.equals("")) {
        overrides = new JsonObject();
      } else {
        overrides = new JsonObject(raw);
      }
      OVERRIDES.put(type, overrides);
    }

    return overrides;
  }


  public static String getOverrideArgs(String type, String method) {
    JsonObject overrides = getOverride(type);

    Object result = overrides.getValue(method);

    if (result == null) {
       return null;
    }

    if (result instanceof String) {
      return (String) result;
    }

    return ((JsonObject) result).getString("args");
  }

  public static String getOverrideReturn(String type, String method) {
    JsonObject overrides = getOverride(type);

    Object result = overrides.getValue(method);

    if (result == null) {
      return null;
    }

    if (result instanceof JsonObject) {
      return ((JsonObject) result).getString("return");
    }

    return null;
  }

  public static void generateDoc(PrintWriter writer, Doc doc, String margin) {
    if (doc != null) {
      writer.print(margin);
      writer.print("/**\n");
      Token.toHtml(doc.getTokens(), margin + " *", Util::renderLinkToHtml, "\n", writer);
      writer.print(margin);
      writer.print(" */\n");
    }
  }

  /**
   * Render a tag link to an html link, this function is used as parameter of the
   * renderDocToHtml function when it needs to render tag links.
   */
  private static String renderLinkToHtml(Tag.Link link) {
    ClassTypeInfo rawType = link.getTargetType().getRaw();
    if (rawType.getModule() != null) {
      String label = link.getLabel().trim();
      if (rawType.getKind() == DATA_OBJECT) {
        if (label.length() == 0) {
          label = rawType.getSimpleName();
        }
        return "<a href=\"../../dataobjects.html#" + rawType.getSimpleName() + "\">" + label + "</a>";
      } else if (rawType.getKind() == ENUM && ((EnumTypeInfo) rawType).isGen()) {
        if (label.length() == 0) {
          label = rawType.getSimpleName();
        }
        return "<a href=\"../../enums.html#" + rawType.getSimpleName() + "\">" + label + "</a>";
      } else {
        if (label.length() > 0) {
          label = "[" + label + "] ";
        }
        Element elt = link.getTargetElement();
        String jsType = rawType.getSimpleName();
        ElementKind kind = elt.getKind();
        if (kind == CLASS || kind == INTERFACE) {
          return label + "{@link " + jsType + "}";
        } else if (kind == METHOD) {
          return label + "{@link " + jsType + "#" + elt.getSimpleName().toString() + "}";
        } else {
          System.out.println("Unhandled kind " + kind);
        }
      }
    }
    return null;
  }
}

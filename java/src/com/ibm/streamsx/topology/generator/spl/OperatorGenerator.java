/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2015  
 */
package com.ibm.streamsx.topology.generator.spl;

import static com.ibm.streamsx.topology.generator.spl.SPLGenerator.splBasename;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONArtifact;
import com.ibm.json.java.JSONObject;
import com.ibm.streams.operator.window.StreamWindow;
import com.ibm.streams.operator.window.StreamWindow.Type;
import com.ibm.streamsx.topology.builder.json.JOperator;
import com.ibm.streamsx.topology.builder.json.JOperator.JOperatorConfig;
import com.ibm.streamsx.topology.context.ContextProperties;

class OperatorGenerator {
    private static final Map<String,String> javaToSPL = new HashMap<>();
    static {
        javaToSPL.put("java.lang.Boolean", "boolean");
        javaToSPL.put("java.lang.String", "rstring");
        javaToSPL.put("java.lang.Byte", "int8");
        javaToSPL.put("java.lang.Short", "int16");
        javaToSPL.put("java.lang.Integer", "int32");
        javaToSPL.put("java.lang.Long", "int64");
        javaToSPL.put("java.lang.Float", "float32");
        javaToSPL.put("java.lang.Double", "float64");
    }

    static String generate(JSONObject graphConfig, JSONObject op)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        noteAnnotations(op, sb);
        parallelAnnotation(op, sb);
        outputClause(op, sb);
        operatorNameAndKind(op, sb);
        inputClause(op, sb);

        sb.append("  {\n");
        windowClause(op, sb);
        paramClause(graphConfig, op, sb);
        configClause(graphConfig, op, sb);
        sb.append("  }\n");

        return sb.toString();
    }

    private static void noteAnnotations(JSONObject op, StringBuilder sb)
            throws IOException {
        
        sourceLocationNote(op, sb);
        portTypesNote(op, sb);
    }
    
    private static void sourceLocationNote(JSONObject op, StringBuilder sb) throws IOException {
        JSONArray ja = (JSONArray) op.get("sourcelocation");
        if (ja == null || ja.isEmpty())
            return;

        JSONArtifact jsource = ja.size() == 1 ? (JSONArtifact) ja.get(0) : ja;

        sb.append("@spl_note(id=\"__spl_sourcelocation\"");
        sb.append(", text=");
        String sourceInfo = jsource.serialize();
        SPLGenerator.stringLiteral(sb, sourceInfo);
        sb.append(")\n");
    }
    
    private static void portTypesNote(JSONObject op, StringBuilder sb) {
        JSONArray ja = (JSONArray) op.get("outputs");
        if (ja == null || ja.isEmpty())
            return;
        for (int i = 0 ; i < ja.size(); i++) {
            JSONObject output = (JSONObject) ja.get(i);
            String type = (String) output.get("type.native");
            if (type == null || type.isEmpty())
                continue;
            sb.append("@spl_note(id=\"__spl_nativeType_output_" + i + "\"");
            sb.append(", text=");
            SPLGenerator.stringLiteral(sb, type);
            sb.append(")\n");
        }
    }

    private static void parallelAnnotation(JSONObject op, StringBuilder sb) {
        Boolean parallel = (Boolean) op.get("parallelOperator");
        if (parallel != null && parallel) {
            sb.append("@parallel(width=");
            Object width = op.get("width");
            if (width instanceof Integer)
                sb.append(Integer.toString((int) width));
            else {
                JSONObject jo = (JSONObject) width;
                String jsonType = (String) jo.get("type");
                if ("submissionParameter".equals(jsonType))
                    addSubmissionParam((JSONObject) jo.get("value"), sb);
                else
                    throw new IllegalArgumentException("Unsupported parallel width specification: " + jo);
            }
            Boolean partitioned = (Boolean) op.get("partitioned");
            if (partitioned != null && partitioned) {
                String parallelInputPortName = (String) op
                        .get("parallelInputPortName");
                parallelInputPortName = splBasename(parallelInputPortName);
                sb.append(", partitionBy=[{port=" + parallelInputPortName
                        + ", attributes=[__spl_hash]}]");
            }
            sb.append(")\n");
        }
    }

    static void outputClause(JSONObject op, StringBuilder sb) {

        JSONArray outputs = (JSONArray) op.get("outputs");
        if (outputs == null || outputs.isEmpty()) {
            sb.append("() ");
            return;
        }

        sb.append("  ( ");
        for (int i = 0; i < outputs.size(); i++) {
            JSONObject output = (JSONObject) outputs.get(i);

            String type = (String) output.get("type");
            // removes the 'tuple' part of the type
            type = type.substring(5);

            String name = (String) output.get("name");
            name = splBasename(name);

            if (i != 0)
                sb.append("; ");

            sb.append("stream");
            sb.append(type);
            sb.append(" ");
            sb.append(name);
        }

        sb.append(") ");
    }

    static void operatorNameAndKind(JSONObject op, StringBuilder sb) {
        String name = (String) op.get("name");
        name = splBasename(name);

        sb.append("as ");
        sb.append(name);
        // sb.append("_op");
        /*
         * JSONArray outputs = (JSONArray) op.get("outputs"); if (outputs ==
         * null || outputs.isEmpty()) { sb.append("_sink"); }
         */

        String kind = (String) op.get("kind");
        sb.append(" = ");
        sb.append(kind);
    }

    static JSONArray getInputs(JSONObject op) {
        JSONArray inputs = (JSONArray) op.get("inputs");
        if (inputs == null || inputs.isEmpty())
            return null;
        return inputs;

    }

    static void inputClause(JSONObject op, StringBuilder sb) {

        JSONArray inputs = getInputs(op);
        if (inputs == null) {
            sb.append("()\n");
            return;
        }

        sb.append("  ( ");
        for (int i = 0; i < inputs.size(); i++) {
            JSONObject input = (JSONObject) inputs.get(i);

            JSONArray conns = (JSONArray) input.get("connections");

            if (i != 0)
                sb.append("; ");

            for (int j = 0; j < conns.size(); j++) {
                String name = (String) conns.get(j);
                name = splBasename(name);

                if (j != 0)
                    sb.append(", ");
                sb.append(name);
            }

            String name = (String) input.get("name");
            sb.append(" as ");
            sb.append(splBasename(name));
        }

        sb.append(")\n");
    }

    static void windowClause(JSONObject op, StringBuilder sb) {
        JSONArray inputs = getInputs(op);
        if (inputs == null) {
            return;
        }

        boolean seenWindow = false;
        for (int i = 0; i < inputs.size(); i++) {

            JSONObject input = (JSONObject) inputs.get(i);
            JSONObject window = (JSONObject) input.get("window");
            if (window == null)
                continue;
            String stype = (String) window.get("type");
            StreamWindow.Type type = StreamWindow.Type.valueOf(stype);
            if (type == Type.NOT_WINDOWED)
                continue;

            if (!seenWindow) {
                sb.append("  window\n");
                seenWindow = true;
            }

            sb.append("    ");
            sb.append(splBasename((String) input.get("name")));
            sb.append(":");
            switch (type) {
            case SLIDING:
                sb.append("sliding,");
                break;
            case TUMBLING:
                sb.append("tumbing,");
                break;
            default:
                throw new IllegalStateException("Internal error");
            }

            appendWindowPolicy(window.get("evictPolicy"),
                    window.get("evictConfig"), sb);

            Object triggerPolicy = window.get("triggerPolicy");
            if (triggerPolicy != null) {
                sb.append(", ");
                appendWindowPolicy(triggerPolicy, window.get("triggerConfig"),
                        sb);
            }

            Boolean partitioned = (Boolean) window.get("partitioned");
            if (partitioned != null && partitioned) {
                sb.append(", partitioned");
            }
            sb.append(";\n");
        }

    }

    static void appendWindowPolicy(Object policyName, Object config,
            StringBuilder sb) {
        StreamWindow.Policy policy = StreamWindow.Policy
                .valueOf((String) policyName);
        switch (policy) {
        case COUNT:
            sb.append("count(");
            sb.append(config);
            sb.append(")");
            break;
        case DELTA:
            break;
        case NONE:
            break;
        case PUNCTUATION:
            break;
        case TIME:
            Long ms = (Long) config;
            double secs = ms.doubleValue() / 1000.0;
            sb.append("time(");
            sb.append(secs);
            sb.append(")");
            break;
        default:
            break;

        }
    }

    static void paramClause(JSONObject graphConfig, JSONObject op,
            StringBuilder sb) {

        JSONArray vmArgs = (JSONArray) graphConfig
                .get(ContextProperties.VMARGS);
        boolean hasVMArgs = vmArgs != null && !vmArgs.isEmpty();

        // VMArgs only apply to Java SPL operators.
        if (hasVMArgs) {

            if (!JOperator.LANGUAGE_JAVA.equals(op.get(JOperator.LANGUAGE)))
                hasVMArgs = false;
        }

        JSONObject params = (JSONObject) op.get("parameters");
        if (!hasVMArgs && (params == null || params.isEmpty())) {
            return;
        }

        sb.append("    param\n");

        for (Object on : params.keySet()) {
            String name = (String) on;
            JSONObject param = (JSONObject) params.get(name);
            sb.append("      ");
            sb.append(name);
            sb.append(": ");
            parameterValue(param, sb);
            sb.append(";\n");
        }

        if (hasVMArgs) {
            JSONObject tmpVMArgParam = new JSONObject();
            tmpVMArgParam.put("value", vmArgs);
            sb.append("      ");
            sb.append("vmArg");
            sb.append(": ");

            parameterValue(tmpVMArgParam, sb);
            sb.append(";\n");
        }
    }

    // Set of "type"s where the "value" in the JSON is printed as-is.
    private static final Set<String> PARAM_TYPES_TOSTRING = new HashSet<>();
    static {
        PARAM_TYPES_TOSTRING.add("enum");
        PARAM_TYPES_TOSTRING.add("spltype");
        PARAM_TYPES_TOSTRING.add("attribute");
    }

    static void parameterValue(JSONObject param, StringBuilder sb) {
        Object value = param.get("value");
        Object type = param.get("type");
        if ("submissionParameter".equals(type)) {
            addSubmissionParam((JSONObject) value, sb);
            return;
        } else if (value instanceof String && !PARAM_TYPES_TOSTRING.contains(type)) {
            if ("ustring".equals(type))
                sb.append("(ustring)");
            SPLGenerator.stringLiteral(sb, value.toString());
            return;
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.size(); i++) {
                if (i != 0)
                    sb.append(", ");
                String sv = (String) a.get(i);
                SPLGenerator.stringLiteral(sb, sv);
            }
            return;
        } else if (value instanceof Number) {
            SPLGenerator.numberLiteral(sb, (Number) value, "unsigned".equals(type));
            return;
        }

        sb.append(value);
    }
    
    private static void addSubmissionParam(JSONObject jo, StringBuilder sb) {
        String name = SPLGenerator.stringLiteral((String) jo.get("name"));
        String valueClassName = (String) jo.get("valueClassName");
        String typeModifier = (String) jo.get("typeModifier");
        String splType = toSPLType(valueClassName, typeModifier);
        Object defaultValue = jo.get("defaultValue");
        if (defaultValue == null)
            sb.append(String.format("(%s) getSubmissionTimeValue(%s)", splType, name));
        else {
            if (splType.startsWith("uint"))
                defaultValue = SPLGenerator.unsignedString(defaultValue);
            defaultValue = SPLGenerator.stringLiteral(defaultValue.toString());
            sb.append(String.format("(%s) getSubmissionTimeValue(%s, %s)", splType, name, defaultValue));
        }
    }
    
    private static String toSPLType(String className, String modifier) {
        String splType = javaToSPL.get(className);
        if (splType == null)
            throw new IllegalArgumentException("Unhandled className "+className);
        if (modifier != null) {
            if ("ustring".equals(modifier))
                splType = "ustring";
            else if ("unsigned".equals(modifier))
                splType = "u" + splType;
        }
        return splType;
    }

    static void configClause(JSONObject graphConfig, JSONObject op,
            StringBuilder sb) {
        if (!JOperator.hasConfig(op))
            return;

        Boolean streamViewability = JOperatorConfig.getBooleanItem(op, "streamViewability");
        String colocationTag = null;
        JSONObject placement = JOperatorConfig.getJSONItem(op, JOperatorConfig.PLACEMENT);
        if (placement != null) {
            // Explicit placement takes precedence.
            colocationTag = (String) placement.get(JOperator.PLACEMENT_EXPLICIT_COLOCATE_ID);
            if (colocationTag == null)
                colocationTag = (String) placement.get(JOperator.PLACEMENT_ISOLATE_REGION_ID);
        }
        JSONObject queue = JOperatorConfig.getJSONItem(op, "queue");
        
        if (streamViewability != null
                || (colocationTag != null && !colocationTag.isEmpty())
                || (queue != null && !queue.isEmpty())
                ) {
            sb.append("  config\n");
        }
        if (streamViewability != null) {
            sb.append("    streamViewability: ");
            sb.append(streamViewability);
            sb.append(";\n");
        }

        if (colocationTag != null && !colocationTag.isEmpty()) {
            sb.append("    placement: partitionColocation(\"");
            sb.append(colocationTag);
            sb.append("\");\n");
        }
        if(queue != null && !queue.isEmpty()){
            sb.append("    threadedPort: queue(");
            sb.append((String)queue.get("inputPortName") + ", ");
            sb.append((String)queue.get("congestionPolicy") + ",");
            sb.append(((Integer)queue.get("queueSize")).toString());
            sb.append(");\n");
        }
      
        
    }
}

package es.upv.pros.ml4iotbp.adaptations.camunda;

import java.io.IOException;
import java.lang.annotation.Native;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import es.upv.pros.ml4iotbp.adaptations.AdaptationExecutor;
import es.upv.pros.ml4iotbp.domain.RuntimePrediction.AdminAction;
import es.upv.pros.ml4iotbp.domain.RuntimePrediction.DirectAction;
import es.upv.pros.ml4iotbp.domain.RuntimePrediction.NativeAction;
import es.upv.pros.ml4iotbp.domain.RuntimePrediction.OnCondition;
import es.upv.pros.ml4iotbp.domain.datasources.process.ProcessContext;


public final class CamundaRestEngineAdapter implements AdaptationExecutor{
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final ProcessContext processContext;
    private String instanceId;


    public CamundaRestEngineAdapter(ProcessContext pc) {
        this.processContext=pc;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
        
    }


    @Override
    public void execute(OnCondition actions, String instanceId) {
        this.instanceId=instanceId;

        List<AdminAction> adminActions=actions.getAdminActions();
        List<NativeAction> nativeActions=actions.getNativeActions(); 
        List<DirectAction> directActions=actions.getDirectActions();

        try{
            if(adminActions!=null){
                for(AdminAction a:adminActions){
                    executeAdministrative(a);
                }
            }else{
                if(nativeActions!=null){
                    for(NativeAction a:nativeActions){
                        executeNative(a);
                    }
                }
                if(directActions!=null){
                    for(DirectAction a:directActions){
                        executeDirect(a);
                    }
                }
            }
        }catch(Exception e){
            e.printStackTrace();
        }
       
    }

    private void executeNative(NativeAction action) throws IOException, InterruptedException {
        switch (normalize(action.getAction())) {
            case "setvariable" -> setVariable(action.getName(), action.getValue());
            case "setmessage" -> correlateMessage(action.getName(), action.getValue());
            case "setsignal" -> broadcastSignal(action.getName(), action.getValue());
            case "executesubprocess" -> executeSubProcess(action.getProcessId());
            default -> throw new IllegalStateException("Unsupported Camunda native action: " + action.getAction());
        }
    }

    private void executeDirect(DirectAction action) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skipCustomListeners", false);
        payload.put("skipIoMappings", false);
        List<Map<String, Object>> instructions = new ArrayList<>();

        switch (normalize(action.getOperation())) {
            case "cancel" -> instructions.add(buildCancelInstruction(action));
            case "activate", "active" -> instructions.add(buildActivateInstruction(action));
            default -> throw new IllegalStateException("Unsupported Camunda direct action: " + action.getOperation());
        }

        payload.put("instructions", instructions);
        post("/process-instance/" + encode(instanceId) + "/modification", payload);
    }

    private Map<String, Object> buildCancelInstruction(DirectAction action)
            throws IOException, InterruptedException {
       /* String requestedTarget = action.getElementId();
        ActivityInstanceRef target = resolveActivityInstance("TODO", requestedTarget);*/
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("type", "cancel");
        cancel.put("activityInstanceId", action.getElementId());
        return cancel;
    }

    private Map<String, Object> buildActivateInstruction(DirectAction action) {
        if (action.getElementId() == null || action.getElementId().isBlank()) {
            throw new IllegalStateException("Activate operation requires a target activity id");
        }
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("type", "startBeforeActivity");
        start.put("activityId", action.getElementId());
        return start;
    }

    private void executeAdministrative(AdminAction action) throws IOException, InterruptedException {
        switch (normalize(action.getTask())) {
            case "cancellation", "cancelinstance", "termination", "terminateinstance" -> delete("/process-instance/" + encode(instanceId));
            case "suspension", "suspend", "suspendinstance" -> put("/process-instance/" + encode(instanceId) + "/suspended", Map.of("suspended", true));
            case "resumption", "resume", "resumeinstance" -> put("/process-instance/" + encode(instanceId) + "/suspended", Map.of("suspended", false));
            default -> throw new IllegalStateException("Unsupported Camunda administrative action: " + action.getTask());
        }
    }

    private void setVariable(String variableName, Object value) throws IOException, InterruptedException {
        put("/process-instance/" + encode(instanceId) + "/variables/" + encode(variableName),
                Map.of("value", value, "type", inferCamundaType(value)));
    }

    private void correlateMessage(String messageName, Object value) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageName", messageName);
        payload.put("processInstanceId", instanceId);
        if (value != null) {
            payload.put("processVariables", Map.of(messageName, Map.of("value", value, "type", inferCamundaType(value))));
        }
        post("/message", payload);
    }

    private void broadcastSignal(String signalName, Object value) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", signalName);
        payload.put("executionId", instanceId);
        if (value != null) {
            payload.put("variables", Map.of(signalName, Map.of("value", value, "type", inferCamundaType(value))));
        }
        post("/signal", payload);
    }

    private void executeSubProcess(String processId) {
        
    }

    

    ActivityInstanceRef resolveActivityInstance(String currentElementId, String requestedTarget)
            throws IOException, InterruptedException {
        JsonNode root = getJson("/process-instance/" + encode(instanceId) + "/activity-instances");
        List<ActivityInstanceRef> activeInstances = flattenActivityInstances(root);
        if (activeInstances.isEmpty()) {
            throw new IllegalStateException("No active activity instances found for process instance " + instanceId);
        }

        if (requestedTarget == null || requestedTarget.isBlank() || "current".equalsIgnoreCase(requestedTarget)) {
            return resolveCurrentActivityInstance(instanceId, currentElementId, activeInstances);
        }

        List<ActivityInstanceRef> matches = activeInstances.stream()
                .filter(ai -> requestedTarget.equals(ai.activityId()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("No active activity instance found for activity '" + requestedTarget
                    + "' in process instance " + instanceId + ". Active elements: "
                    + activeInstances.stream().map(ActivityInstanceRef::activityId).distinct().collect(Collectors.joining(", ")));
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Activity '" + requestedTarget + "' has " + matches.size()
                    + " active instances. The DSL should disambiguate which one to cancel.");
        }
        return matches.get(0);
    }

    private ActivityInstanceRef resolveCurrentActivityInstance(String processInstanceId,
                                                               String currentElementId,
                                                               List<ActivityInstanceRef> activeInstances) {
        if (currentElementId != null && !currentElementId.isBlank() && !"unknown-element".equals(currentElementId)) {
            List<ActivityInstanceRef> matches = activeInstances.stream()
                    .filter(ai -> currentElementId.equals(ai.activityId()))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                throw new IllegalStateException("Current BPMN element '" + currentElementId + "' maps to " + matches.size()
                        + " active activity instances in process instance " + processInstanceId
                        + ". The current DSL runtime context is not specific enough to choose one safely.");
            }
        }

        List<ActivityInstanceRef> leafInstances = activeInstances.stream()
                .filter(ActivityInstanceRef::leaf)
                .sorted(Comparator.comparing(ActivityInstanceRef::activityId))
                .toList();
        if (leafInstances.size() == 1) {
            return leafInstances.get(0);
        }

        throw new IllegalStateException("Cannot resolve 'current' activity instance for process instance " + processInstanceId
                + ". Runtime context currentElementId='" + currentElementId + "'. Active leaf elements: "
                + leafInstances.stream().map(ActivityInstanceRef::activityId).collect(Collectors.joining(", ")));
    }

    private List<ActivityInstanceRef> flattenActivityInstances(JsonNode node) {
        List<ActivityInstanceRef> acc = new ArrayList<>();
        walkActivityInstanceTree(node, acc);
        return acc;
    }

    private void walkActivityInstanceTree(JsonNode node, List<ActivityInstanceRef> acc) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        JsonNode childActivityInstances = node.path("childActivityInstances");
        JsonNode childTransitionInstances = node.path("childTransitionInstances");
        boolean hasChildActivities = childActivityInstances.isArray() && childActivityInstances.size() > 0;
        boolean hasChildTransitions = childTransitionInstances.isArray() && childTransitionInstances.size() > 0;

        String activityId = textOrNull(node.get("activityId"));
        String activityInstanceId = textOrNull(node.get("id"));
        if (activityId != null && activityInstanceId != null) {
            acc.add(new ActivityInstanceRef(activityInstanceId, activityId, !(hasChildActivities || hasChildTransitions)));
        }

        if (hasChildActivities) {
            for (JsonNode child : childActivityInstances) {
                walkActivityInstanceTree(child, acc);
            }
        }
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder(path).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "GET", path);
        return mapper.readTree(response.body());
    }

    private void post(String path, Object payload) throws IOException, InterruptedException {
        System.out.println(path);
        System.out.println(mapper.writeValueAsString(payload));
        //sendWithBody("POST", path, payload);
    }

    private void put(String path, Object payload) throws IOException, InterruptedException {
        sendWithBody("PUT", path, payload);
    }

    private void sendWithBody(String method, String path, Object payload) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(payload);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = requestBuilder(path).header("Content-Type", "application/json");
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(publisher).build();
            case "PUT" -> builder.PUT(publisher).build();
            default -> throw new IllegalArgumentException("Unsupported method " + method);
        };
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, method, path);
    }

    private void delete(String path) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder(path).DELETE().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "DELETE", path);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(processContext.getHost() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        return builder;
    }

    private void ensureSuccess(HttpResponse<String> response, String method, String path) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Camunda REST " + method + " " + path + " failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
    }

    private String inferCamundaType(Object value) {
        if (value == null) {
            return "Null";
        }
        if (value instanceof Boolean || "true".equalsIgnoreCase(String.valueOf(value)) || "false".equalsIgnoreCase(String.valueOf(value))) {
            return "Boolean";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "Long";
        }
        if (value instanceof Number) {
            return "Double";
        }
        return "String";
    }

    private static String trimTrailingSlash(String value) {
        Objects.requireNonNull(value, "engineRestUrl must not be null");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String buildAuthorizationHeader(String username, String password, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            return "Bearer " + bearerToken;
        }
        if (username != null && !username.isBlank()) {
            String raw = username + ":" + Optional.ofNullable(password).orElse("");
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll(" ", "").toLowerCase();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    record ActivityInstanceRef(String activityInstanceId, String activityId, boolean leaf) {}
}

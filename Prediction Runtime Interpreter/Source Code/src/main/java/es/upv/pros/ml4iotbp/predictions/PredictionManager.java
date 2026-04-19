package es.upv.pros.ml4iotbp.predictions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import es.upv.pros.ml4iotbp.adaptations.AdaptationExecutor;
import es.upv.pros.ml4iotbp.adaptations.camunda.CamundaRestEngineAdapter;
import es.upv.pros.ml4iotbp.domain.RuntimePrediction;
import es.upv.pros.ml4iotbp.domain.RuntimePrediction.OnCondition;
import es.upv.pros.ml4iotbp.domain.datasources.process.ProcessContext;

public class PredictionManager {
    private final HttpClient client;
    private final String baseURL= "http://127.0.0.1:8000/predict/";
    private final RuntimePrediction runtimePrediction;
    private final AdaptationExecutor adaptationExecutor;

    public PredictionManager(Map<String,RuntimePrediction> runtimePredictions, ProcessContext pc){
        this.runtimePrediction=runtimePredictions.values().iterator().next();

        switch (pc.getEngine().toLowerCase()) {
            case "camunda": adaptationExecutor=new CamundaRestEngineAdapter(pc);
                break;
            default: adaptationExecutor=new CamundaRestEngineAdapter(pc);
                break;
        }

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

    }

    public void managePrediction(ObjectNode jsonRow, String instanceId){

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode row= mapper.createObjectNode();
        ArrayNode array= mapper.createArrayNode();
        array.add(jsonRow);
        row.set("instances", array);

            String model=runtimePrediction.getModel();

            HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseURL+model))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(row.toString()))
                        .build();
            
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // Resultado
                System.out.println("Status code: " + response.statusCode());
                System.out.println("Response body: " + response.body());

                ObjectNode prediction = (ObjectNode) mapper.readTree(response.body());
                JsonNode values=prediction.get("predictions");
                String result=values.get(0).asText();
                System.out.println("RESULT:"+result+"=="+runtimePrediction.getCondition().getPositiveClass());


                if(result.equals(runtimePrediction.getCondition().getPositiveClass())){
                    if(runtimePrediction.getCondition().getOnTrue()!=null){
                        OnCondition actions=runtimePrediction.getCondition().getOnTrue();
                        this.adaptationExecutor.execute(actions, instanceId);
                    }
                }else{
                    if(runtimePrediction.getCondition().getOnFalse()!=null){
                        OnCondition actions=runtimePrediction.getCondition().getOnTrue();
                        this.adaptationExecutor.execute(actions, instanceId);
                    }
                }

                
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

        
    }

    public RuntimePrediction getRuntimePrediction(){
        return this.runtimePrediction;
    }

}

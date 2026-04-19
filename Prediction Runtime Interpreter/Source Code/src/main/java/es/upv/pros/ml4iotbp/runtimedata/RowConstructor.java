package es.upv.pros.ml4iotbp.runtimedata;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import es.upv.pros.ml4iotbp.connectors.DataVar;
import es.upv.pros.ml4iotbp.connectors.process.ProcessEvent;
import es.upv.pros.ml4iotbp.domain.ML4IoTBPDocument;
import es.upv.pros.ml4iotbp.domain.dataset.DataItem;
import es.upv.pros.ml4iotbp.domain.dataset.Dataset;
import es.upv.pros.ml4iotbp.domain.dataset.FeatureMapping;
import es.upv.pros.ml4iotbp.domain.dataset.FeatureRef;
import es.upv.pros.ml4iotbp.domain.datasources.Variable;
import es.upv.pros.ml4iotbp.domain.datasources.iot.IoTDataSource;
import es.upv.pros.ml4iotbp.domain.features.CompositeFeatureFrom;
import es.upv.pros.ml4iotbp.domain.features.Feature;
import es.upv.pros.ml4iotbp.domain.features.Feature.Anchor;
import es.upv.pros.ml4iotbp.domain.features.SimpleFeatureFrom;
import es.upv.pros.ml4iotbp.domain.features.SourceSelection;
import es.upv.pros.ml4iotbp.predictions.PredictionManager;
import es.upv.pros.ml4iotbp.utils.DurationParser;

public class RowConstructor {

        private Map<String,Map<String,List<Feature>>> anchoredFeatures;
        private Map<String, Dataset> datasetMap;
        private Map<String, RowValue> row;
        private List<String> fieldsInOrder;
        private Map<String, String> featureRowMap;
        private Map<String, Map<String, DataVar>> instanceVars;
        private ML4IoTBPDocument doc;
        private PredictionManager predictionManager;
        public PredictionManager getPredictionManager(){return predictionManager;}
        

        public RowConstructor(ML4IoTBPDocument doc, PredictionManager predictionManager) {
            this.doc=doc;
            this.predictionManager=predictionManager;

            this.row=new Hashtable<String, RowValue>();
            this.instanceVars=new Hashtable<String, Map<String, DataVar>>();
            this.fieldsInOrder=new ArrayList<String>();
            this.featureRowMap= new Hashtable<String, String>();
            this.datasetMap=doc.getDataset();
            this.anchoredFeatures=new HashMap<String,Map<String,List<Feature>>>();

            doc.getFeatures().forEach((name, feature) -> {
                    feature.setName(name);
                    if(feature.getAnchor()!=null){
                        Map<String,List<Feature>> elements=getElementMap(feature);
                        addEventFeature(elements, feature);
                    }
            });

            if(predictionManager.getRuntimePrediction().getTrigger().getFrom()!=null){
                String element=predictionManager.getRuntimePrediction().getTrigger().getFrom().keySet().iterator().next();
                String event=predictionManager.getRuntimePrediction().getTrigger().getFrom().get(element);
                
                Anchor anchor=new Anchor();
                anchor.setElement(element);
                anchor.setEvent(event);
                
                Feature triggerFrom=new Feature();
                triggerFrom.setName("TriggerFrom");
                triggerFrom.setAnchor(anchor);
    
                 Map<String,List<Feature>> elements=getElementMap(triggerFrom);
                addEventFeature(elements, triggerFrom);
            }
            if(predictionManager.getRuntimePrediction().getTrigger().getTo()!=null){
                String element=predictionManager.getRuntimePrediction().getTrigger().getTo().keySet().iterator().next();
                String event=predictionManager.getRuntimePrediction().getTrigger().getTo().get(element);
                
                Anchor anchor=new Anchor();
                anchor.setElement(element);
                anchor.setEvent(event);
                
                Feature triggerTo=new Feature();
                triggerTo.setName("TriggerTo");
                triggerTo.setAnchor(anchor);
    
                 Map<String,List<Feature>> elements=getElementMap(triggerTo);
                addEventFeature(elements, triggerTo);
            }
            

            //showFeature();

            
            try {
                init();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        public void compute(ProcessEvent event){

            if(!event.getEventName().equals(ProcessEvent.START_INSTANCE) && !event.getEventName().equals(ProcessEvent.END_INSTANCE)){
                addInstanceVars(event.getProcessInstanceId(), event.getVariables());

                List<Feature> features=anchoredFeatures.get(event.getElementId()).get(event.getEventName());
                for(Feature f:features){
                    if(!f.getName().equals("TriggerFrom") && !f.getName().equals("TriggerTo")){
                        if(isProcessFeatured(f)){
                            calculateProcessFeature(event.getProcessInstanceId(),f);
                        }else{
                            Long timestamp=event.getTimeStamp();
                            if(timestamp==null) timestamp=Instant.now().toEpochMilli();
                            calculateIoTFeature(event.getProcessInstanceId(),timestamp,f);
                        }
                    }
                }
            }

        }

        private void init() throws IOException{
    
            Dataset dataSet=datasetMap.entrySet().iterator().next().getValue();
       
            for(DataItem column: dataSet.getData()){
                if(column instanceof FeatureRef){
                    FeatureRef col=(FeatureRef )column;  
                    row.put(col.getAlias(), new RowValue("",""));
                    featureRowMap.put(col.getFeatureId(), col.getAlias());
                    fieldsInOrder.add(col.getAlias());
                }else{
                    FeatureMapping col=(FeatureMapping)column;
                    for(Map.Entry<String,String> mapping: col.getFields().entrySet()){
                        String label=mapping.getKey();
                        row.put(label, new RowValue("",""));
                        featureRowMap.put(col.getFeatureId()+"."+mapping.getValue(), label);
                        fieldsInOrder.add(label);
                    };
                }
            };
        }

        public void getPrediction(String instanceId){
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode rowJson= mapper.createObjectNode();
           
            String rowText=instanceId+",";
            for(String label: fieldsInOrder){
                rowText+=row.get(label).getValue()+",";
                this.addValueToJson(rowJson,label, row.get(label).getType(), row.get(label).getValue());
            }
            
            rowText=rowText.substring(0, rowText.length()-1);


            System.out.println("Geting prediction for:"+rowText);
            System.out.println(rowJson.toPrettyString());

            this.predictionManager.managePrediction(rowJson,instanceId);
             
        }

        private Map<String,List<Feature>> getElementMap(Feature feature){
            String anchor=feature.getAnchor().getElement();
            if(anchoredFeatures.get(anchor)==null){
                Map<String,List<Feature>> elements = new HashMap<String,List<Feature>>();
                anchoredFeatures.put(anchor,elements);
                return elements;
            }else{
                return anchoredFeatures.get(anchor);
            }
        }

        private void addEventFeature(Map<String,List<Feature>> elements, Feature feature){
            String anchor=feature.getAnchor().getEvent();
            
            if(elements.get(anchor)==null){
                List<Feature> featureList=new ArrayList<Feature>();
                featureList.add(feature);
                elements.put(anchor, featureList);
            }else{
                elements.get(anchor).add(feature);
            }
        }

        private void calculateProcessFeature(String instance, Feature f){
            List<String> featureFields=getFeatureFields(f);
            switch(f.getOperation()){
                case "include": 
                                featureFields.forEach((field)->{
                                    String label=featureRowMap.get(f.getName()+"."+field);
                                    if(label!=null){
                                        DataVar var=this.instanceVars.get(instance).get(field);
                                        row.put(label, new RowValue(var.getValue(), var.getType()));
                                    }
                                }); 
                                break;
                case "future_event_exists":
                                String label=featureRowMap.get(f.getName());
                                if(label!=null){
                                    DataVar var=this.instanceVars.get(instance).get(f.getField());
                                    row.put(label, new RowValue(var.getValue(),var.getType()));
                                }
                                break;
                default:
                                String label2=featureRowMap.get(f.getName());
                                if(label2!=null){
                                    List<DataVar> vars=new ArrayList<DataVar>();
                                    for (String field : featureFields){ 
                                        vars.add(this.instanceVars.get(instance).get(field));
                                    }
                                    double result=operate(f.getOperation(),vars);          
                                    row.put(label2, new RowValue(result,"Double"));
                                }
                                break;
            }
        }

        private void calculateIoTFeature(String processInstance, long eventTimeStamp, Feature f){
            
            String dsName=((SimpleFeatureFrom)f.getFrom()).getSource();

            IoTDataSource ds= doc.getIotDataSources().get(dsName);
            if(ds==null){
                for(IoTDataSource ds2:doc.getIotDataSources().values()){
                    for(Map.Entry<String, IoTDataSource> ds3: ds2.getSensors().entrySet()){
                        if(ds3.getKey().equals(dsName)) ds=ds3.getValue();
                    };
                }
            }
            if(ds!=null){
                Map<String,DataVar> varMap=new Hashtable<String,DataVar>();
                for(Variable var:ds.getSchema()){
                    DataVar v=new DataVar();
                    v.setName(var.getVarName());
                    v.setType("string");
                    varMap.put(v.getName(), v);
                }
                
                IoTRepository ioTRepository=IoTRepository.getCurrentInstance();
                List<String> featureFields=getFeatureFields(f);
                try {
                    long initInstant=0;
                    try{
                        initInstant=eventTimeStamp-DurationParser.parseTime(f.getWindow()).toMillis();
                        /*System.out.println("---------");
                        System.out.println("--------->"+initInstant);
                        System.out.println("--------->"+eventTimeStamp);
                        System.out.println("--------->"+DurationParser.parseTime(f.getWindow()).toMillis());
                        System.out.println("---------");*/
                    }catch(IllegalArgumentException e){
                        //Comprobamos si es una resta de processVars
                        if(f.getWindow().indexOf("-")>0){
                            String values[]=f.getWindow().split("-");
                            long value1=Long.parseLong(this.instanceVars.get(processInstance).get(values[0].trim()).getValue().toString());
                            long value2=Long.parseLong(this.instanceVars.get(processInstance).get(values[1].trim()).getValue().toString());
                            initInstant=eventTimeStamp-(value1-value2);
                        }
                    }


                    ObjectMapper mapper = new ObjectMapper();
                    ArrayNode list=mapper.createArrayNode();
                    long window=eventTimeStamp-initInstant;
                    int attemps=0;
                    while(list.size()==0 && initInstant>=0 && attemps<5){
                        attemps++;
                        String jsonData=ioTRepository.getCurrentInstance().selectWindow(dsName, initInstant, eventTimeStamp);
                        list = (ArrayNode)mapper.readTree(jsonData);
                        initInstant-=window;
                    }
                    
                    
                    List<DataVar> varsToProcess=new ArrayList<DataVar>();
                    for(int i=0;i<list.size();i++){
                        ObjectNode iotData= (ObjectNode) list.get(i);
                        for(String field:featureFields){
                            DataVar v=varMap.get(field);
                            DataVar newV=new DataVar();
                            newV.setName(v.getName());
                            newV.setType(v.getType());
                            newV.setValue(iotData.findValue(field).textValue());
                            varsToProcess.add(newV);
                        }
                    }
                    double result=operate(f.getOperation(),varsToProcess);
                    String label=featureRowMap.get(f.getName());
                    row.put(label, new RowValue(result,"Double"));
                } catch (JsonProcessingException | SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        private double operate(String op, List<DataVar> vars){
            double result = Double.NaN;
            for (DataVar var : vars) {
                double value = toDouble(var);
                if (Double.isNaN(result)) {
                    result = value;
                } else {
                    switch (op) {
                        case "avg":
                        case "sum": result += value; break;
                        case "substract": result -= value; break;
                        case "min": if(value<result) result=value; break;
                        case "max": if(value>result) result=value; break;
                    }
                }
            }

            if(Double.isNaN(result)) return result;
            else{
                if(op.equals("avg")) result=result/(float)vars.size();
                //BigDecimal bd = BigDecimal.valueOf(result).setScale(2, RoundingMode.HALF_UP);
                double valorRedondeado = Math.round(result * 100.0) / 100.0;
                return valorRedondeado;
            }

            
        }

        private double toDouble(DataVar var) {
            Object v = var.getValue();
            String type = var.getType().toLowerCase();
            return switch (type) {
                case "int", "integer" -> ((Number) v).intValue();
                case "timestamp"     -> ((Number) v).longValue();
                case "long"          -> ((Number) v).longValue();
                case "float"         -> ((Number) v).floatValue();
                case "double"        -> ((Number) v).doubleValue();
                case "string" -> Double.parseDouble(v.toString());
                default -> throw new IllegalArgumentException(
                        "Unsupported numeric type for subtract: " + var.getType()
                );
            };
        }

        private List<String> getFeatureFields(Feature f){
            List<String> fields=new ArrayList<String>();
            if(f.getFrom() instanceof SimpleFeatureFrom){
                if(f.getField()!=null) fields.add(f.getField());
                else{
                    f.getFields().forEach((field)->{
                        fields.add(field);
                    });
                }
            }else{
                CompositeFeatureFrom from=((CompositeFeatureFrom)f.getFrom());
                for(Map.Entry<String, SourceSelection> entry: from.getSources().entrySet()){
                    SourceSelection source=entry.getValue();
                    if(source.getField()!=null) fields.add(source.getField());
                    if(source.getFields()!=null){
                        source.getFields().forEach((field)-> {fields.add(field);}); 
                    }
                }
            }
            return fields;
        }

        private void addInstanceVars(String instanceId, List<DataVar> variables){
            Map<String,DataVar> vars=this.instanceVars.get(instanceId);
            if(vars==null) vars=new Hashtable<String,DataVar>();
            for(DataVar v: variables){
                vars.put(v.getName(),v);
            }
            this.instanceVars.put(instanceId, vars);
        }

        public Map<String, Map<String, List<Feature>>> getAnchoredFeatures() {
            return anchoredFeatures;
        }

        private boolean isProcessFeatured(Feature f){
            if(f.getFrom() instanceof SimpleFeatureFrom){
                SimpleFeatureFrom from=(SimpleFeatureFrom)f.getFrom();
                return doc.getProcessDataSources().get(from.getSource())!=null;
            }else{
                CompositeFeatureFrom from=(CompositeFeatureFrom)f.getFrom();
                for(String source:from.getSources().keySet()){
                    if(doc.getProcessDataSources().get(source)==null) return false;
                }
                return true;
            }
        }

        private void showFeature(){
             anchoredFeatures.forEach((element, events) -> {
                    System.out.println(element+": ");
                    events.forEach((event, featureList) -> {
                        System.out.print("  "+event+": ");
                        featureList.forEach((f)->{System.out.print(" "+f.getName());});
                        System.out.println(" ");
                    });
            });
        }


        private void addValueToJson(ObjectNode json, String varName, String type, Object value){
            type=type.toLowerCase();
            switch(type){
                case "string":
                case "date":
                case "dateTime": json.put(varName,value.toString());break;
                case "double":
                case "number": 
                case "float": json.put(varName,(Double)value); break;
                case "integer": json.put(varName,(Integer)value); break;
                case "boolean": json.put(varName,(Boolean)value); break;
            }
        }
}


class RowValue{
    private Object value;
    private String type;

    public RowValue(Object value,String type){
        this.value=value;
        this.type=type;
    }

    public Object getValue() {
        return value;
    }
    public void setValue(Object value) {
        this.value = value;
    }
    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }

    
}

package es.upv.pros.ml4iotbp.app;

import java.io.InputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import es.upv.pros.ml4iotbp.connectors.iot.IoTDataSourceConnector;
import es.upv.pros.ml4iotbp.connectors.process.camunda.ProcessDataSourceConnector;
import es.upv.pros.ml4iotbp.domain.ML4IoTBPDocument;
import es.upv.pros.ml4iotbp.io.YamlLoader;
import es.upv.pros.ml4iotbp.predictions.PredictionManager;
import es.upv.pros.ml4iotbp.runtimedata.RowConstructor;

public class App {

    
     public static void main(String[] args) {
        try {
         
            InputStream yamlStream = App.class.getClassLoader().getResourceAsStream("logistics.ml4iotbp.dsl.yaml");
            InputStream schemaStream = App.class.getClassLoader().getResourceAsStream("ML4IoTBP-Grammar.json");

            if (yamlStream == null || schemaStream == null) {
                throw new IllegalStateException("No se encontraron los recursos YAML o JSON-Schema");
            }

            YamlLoader loader = new YamlLoader();

            // 1) Validación contra JSON-Schema
            System.out.println("🔍 Validating YAML against JSON-Schema...");
            loader.validate(yamlStream, schemaStream);
            System.out.println("✅ Validation OK\n");

            // 2) Parseo YAML -> POJOs
            yamlStream = App.class.getClassLoader().getResourceAsStream("logistics.ml4iotbp.dsl.yaml");
            ML4IoTBPDocument doc = loader.load(yamlStream);
            System.out.println("📄 YAML parsed successfully\n");

            // The Runtime Adapter use the current time to extract new Process instances
            ZonedDateTime now = ZonedDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
            String formattedNow = now.format(formatter);
            formattedNow="2026-03-24T12:40:00.920+0100";
            //System.out.println(formattedNow);

            doc.getProcessContext().setInstanceTime(formattedNow);

       
            // 3A) Inspección de IoT Data Sources
            if (doc.getIotDataSources() != null) {
                doc.getIotDataSources().forEach((id, ds) -> {
                    ds.setId(id);
                });
            }
            //Logger.showIoTDataSources(doc);
            new IoTDataSourceConnector().connect(doc.getIotDataSources());

            // 3B) Inspección de Process Data Sources
            if (doc.getProcessDataSources() != null) {
                doc.getProcessDataSources().forEach((name, pds) -> {
                        pds.setPdsName(name);
                });
            }
            Logger.showProcessDataSources(doc);
            PredictionManager predictionManager=new PredictionManager(doc.getRuntimePredictions(),doc.getProcessContext());
            RowConstructor rowConstructor=new RowConstructor(doc,predictionManager);
            
            new ProcessDataSourceConnector().connect(doc.getProcessContext(),doc.getProcessDataSources(),rowConstructor);

            

            // 4) Features
            //Logger.showFeatures(doc);

            // 5) Datasets
            //Logger.showDataSets(doc);

            // 6) Models
            //Logger.showMLModels(doc);

            // 7) Runtime Predictions
            Logger.showRuntimePredictions(doc);


        } catch (Exception e) {
            System.err.println("❌ Error while parsing YAML:");
            e.printStackTrace();
        }
    }
}

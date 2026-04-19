# A DSL for Supporting the Integration of Runtime Machine Learning Predictions into IoT-Enhanced BPs

The integration of Machine Learning (ML) into IoT-Enhanced Business Processes (BPs) offers significant opportunities to enable predictive and adaptive process behavior. However, existing approaches provide limited support for systematically incorporating predictions into IoT-Enhanced BPs at runtime. This paper addresses this challenge from a Model-Driven Engineering perspective. In particular, we extend a previously proposed Domain-Specific Language (DSL) with constructs that enable the declarative specification of runtime prediction behavior. The proposed extensions allow users to define when ML predictions should be triggered, how runtime data is collected and aligned with training data, and how prediction outcomes influence process execution through different categories of actions.

To operationalize the DSL, we implement a runtime interpreter that provides the required execution semantics and enables the seamless integration of ML inference into running process instances. The approach is evaluated through a proof-of-concept implementation and an empirical usability study. The results indicate that the DSL is both readable and usable, supporting users in specifying runtime predictive behavior effectively. These findings suggest that the proposed approach facilitates a more systematic and maintainable integration of ML capabilities into IoT-Enhanced BPs.

# Folders

The content of the folders is the following:

* "DSL Grammar" contains the JSON Schema that defines the structural grammar of the DSL.
* "Prediction Runtime Interpreter" contains: (1) the Java source code of the software infrastructure that interprets DSL definitions in order to integrate ML runtime predictions into IoT-Enhanced BPs, and (2) the artefacts used to validate this infrastructure through different scenarios.
* "Usability Experiment" contains the artefacts produced in a usability experiment of the DSL.
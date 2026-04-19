package es.upv.pros.ml4iotbp.adaptations;

import es.upv.pros.ml4iotbp.domain.RuntimePrediction.OnCondition;

public interface AdaptationExecutor {

    public void execute(OnCondition actions, String instanceId);

}

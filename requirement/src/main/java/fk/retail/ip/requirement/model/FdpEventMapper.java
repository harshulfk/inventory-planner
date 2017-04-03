package fk.retail.ip.requirement.model;

import fk.retail.ip.fdp.model.FdpEventPayload;
import fk.retail.ip.fdp.model.FdpRequirementEventData;

import java.util.List;
/**
 * Created by yogeshwari.k on 17/03/17.
 */
public interface FdpEventMapper<T,V> {
    List<FdpEventPayload<T>> convertToEventPayload(Object id, V eventToPush);
}

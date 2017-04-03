package fk.retail.ip.requirement.model;

import fk.retail.ip.fdp.model.FdpEntityPayload;

/**
 * Created by yogeshwari.k on 16/03/17.
 */
public interface FdpEntityMapper<T, V> {

    FdpEntityPayload<T> convertToEntityPayload(Object id, V entityToPush);

}

package fk.retail.ip.requirement.service;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;
import com.google.common.io.CharStreams;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import fk.retail.ip.requirement.internal.command.PayloadCreationHelper;
import fk.retail.ip.requirement.internal.entities.AbstractEntity;
import fk.retail.ip.requirement.internal.entities.Requirement;
import fk.retail.ip.requirement.internal.enums.FdpRequirementEventType;
import fk.retail.ip.requirement.internal.enums.OverrideKey;
import fk.retail.ip.requirement.internal.enums.RequirementApprovalState;
import fk.retail.ip.requirement.internal.repository.RequirementRepository;
import fk.retail.ip.requirement.internal.command.FdpIngestor;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import fk.retail.ip.requirement.model.RequirementChangeMap;
import fk.retail.ip.requirement.model.RequirementChangeRequest;
import org.json.JSONObject;

/**
 *
 * @author Pragalathan M<pragalathan.m@flipkart.com>
 */
public class ApprovalService<E extends AbstractEntity> {

    private JSONObject actions;

    @Inject
    public ApprovalService(@Named("actionConfiguration") String actionConfiguration) {
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream(actionConfiguration))) {
            actions = new JSONObject(CharStreams.toString(reader));
        } catch (Exception ex) {
            throw new RuntimeException("Error in reading config file", ex);
        }
    }

    public String getTargetState(String action) {
        return actions.getJSONObject(action).getString("nextState");
    }

    public void changeState(List<E> items,
            String userId,
            String action,
            Function<E, String> getter,
            StageChangeAction<E>... actionListeners) {
        JSONObject stateMachine = actions.getJSONObject(action);
        String fromState = stateMachine.getString("currentState");
        String toState = stateMachine.getString("nextState");
        boolean forward = stateMachine.getBoolean("isForward");
        validate(items, fromState, getter);
        for (StageChangeAction<E> consumer : actionListeners) {
            consumer.execute(userId, fromState, toState, forward, items);
        }
    }

    private void validate(List<E> items, String fromState, Function<E, String> getter) {
        for (E item : items) {
            String currentState = getter.apply(item);
            if (!currentState.equals(fromState)) {
                throw new IllegalStateException("Entity[id=" + item.getId() + "] is not in " + fromState + " state");
            }
        }
    }

    public static interface StageChangeAction<E extends AbstractEntity> {

        void execute(String userId,
                String fromState,
                String toState,
                boolean forward,
                List<E> entity);
    }

    public static class CopyOnStateChangeAction implements StageChangeAction<Requirement> {

        private RequirementRepository repository;
        private FdpIngestor fdpIngestor;
        private PayloadCreationHelper payloadCreationHelper;

        public CopyOnStateChangeAction(RequirementRepository requirementRepository, FdpIngestor fdpIngestor, PayloadCreationHelper payloadCreationHelper) {
            this.repository = requirementRepository;
            this.fdpIngestor = fdpIngestor;
            this.payloadCreationHelper = payloadCreationHelper;
        }

        @Override
        public void execute(String userId, String fromState, String toState, boolean forward, List<Requirement> entities) {
            Map<String, List<Requirement>> fsnToRequirements = entities.stream().collect(Collectors.groupingBy(Requirement::getFsn));
            List<Requirement> toEntities = repository.findEnabledRequirementsByStateFsn(toState, fsnToRequirements.keySet());
            Table<String, String, Requirement> cdoStateEntityMap = getCDOEntityMap(toState,  fsnToRequirements.keySet());
            boolean isIPCReviewState = RequirementApprovalState.IPC_REVIEW.toString().equals(toState);
            List<RequirementChangeRequest> requirementChangeRequestList = Lists.newArrayList();
            fsnToRequirements.keySet().stream().forEach((fsn) -> {
                fsnToRequirements.get(fsn).stream().forEach((entity) -> {
                    Optional<Requirement> toStateEntity = toEntities.stream().filter(e -> e.getWarehouse().equals(entity.getWarehouse()) && e.getFsn().equals(entity.getFsn())).findFirst();
                    RequirementChangeRequest requirementChangeRequest = new RequirementChangeRequest();
                    List<RequirementChangeMap> requirementChangeMaps = Lists.newArrayList();
                    if (forward) {
                        //Add APPROVE events to fdp request
                        requirementChangeMaps.add(payloadCreationHelper.createChangeMap(OverrideKey.STATE.toString(), fromState, toState, FdpRequirementEventType.APPROVE.toString(), "Moved to next state", userId));
                        if (toStateEntity.isPresent()) {
                            toStateEntity.get().setQuantity(entity.getQuantity());
                            if(isIPCReviewState) {
                                toStateEntity.get().setQuantity(cdoStateEntityMap.get(entity.getFsn(), entity.getWarehouse()).getQuantity());
                            }
                            toStateEntity.get().setSupplier(entity.getSupplier());
                            toStateEntity.get().setApp(entity.getApp());
                            toStateEntity.get().setSla(entity.getSla());
                            toStateEntity.get().setPreviousStateId(entity.getId());
                            toStateEntity.get().setUpdatedBy(userId);
                            toStateEntity.get().setCurrent(true);
                            entity.setCurrent(false);
                            requirementChangeRequest.setRequirement(toStateEntity.get());
                            entity.setUpdatedBy(userId);
                        } else {
                            Requirement newEntity = new Requirement(entity);
                            if(isIPCReviewState) {
                                newEntity.setQuantity(cdoStateEntityMap.get(entity.getFsn(), entity.getWarehouse()).getQuantity());
                            }
                            newEntity.setState(toState);
                            newEntity.setCreatedBy(userId);
                            newEntity.setPreviousStateId(entity.getId());
                            newEntity.setCurrent(true);
                            newEntity.setUpdatedBy(userId);
                            repository.persist(newEntity);
                            entity.setCurrent(false);
                            requirementChangeRequest.setRequirement(newEntity);
                            entity.setUpdatedBy(userId);
                        }
                    } else {
                        //Add CANCEL events to fdp request
                        requirementChangeMaps.add(payloadCreationHelper.createChangeMap(OverrideKey.STATE.toString(), fromState, toState, FdpRequirementEventType.CANCEL.toString(), "Moved to previous state", userId));
                        toStateEntity.ifPresent(e -> { // this will always be present
                            e.setCurrent(true);
                            entity.setCurrent(false);
                            requirementChangeRequest.setRequirement(toStateEntity.get());
                            entity.setUpdatedBy(userId);
                            e.setUpdatedBy(userId);
                        });
                    }

                    requirementChangeRequest.setRequirementChangeMaps(requirementChangeMaps);
                    requirementChangeRequestList.add(requirementChangeRequest);
                });
            });
            //Push APPROVE and CANCEL events to fdp
            fdpIngestor.pushToFdp(requirementChangeRequestList);
        }

        private Table<String,String,Requirement> getCDOEntityMap(String toState, Set<String> fsns) {
            Table<String, String, Requirement> cdoStateRequirementMap = HashBasedTable.create();
            boolean isIPCReviewState = RequirementApprovalState.IPC_REVIEW.toString().equals(toState);
            if (isIPCReviewState) {
                String cdoState = RequirementApprovalState.CDO_REVIEW.toString();
                List<Requirement> cdoReviewEntities = repository.findEnabledRequirementsByStateFsn(cdoState, fsns);
                cdoReviewEntities.forEach((entity) -> {
                    cdoStateRequirementMap.put(entity.getFsn(), entity.getWarehouse(), entity);
                });
            }
            return cdoStateRequirementMap ;
        }

        private Table<String,String,Requirement> getToStateEntity(String toState, Set<String> fsns) {
            Table<String, String, Requirement> toStateRequirementMap = HashBasedTable.create();
            List<Requirement> toStateEntities = repository.findEnabledRequirementsByStateFsn(toState, fsns);
            toStateEntities.forEach((entity) -> {
                toStateRequirementMap.put(entity.getFsn(), entity.getWarehouse(), entity);
            });

            return toStateRequirementMap ;
        }
    }
}

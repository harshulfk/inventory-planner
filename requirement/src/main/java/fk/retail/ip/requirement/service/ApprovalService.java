package fk.retail.ip.requirement.service;

import fk.retail.ip.requirement.internal.Constants;
import fk.retail.ip.requirement.internal.entities.AbstractEntity;
import fk.retail.ip.requirement.internal.entities.Requirement;
import fk.retail.ip.requirement.internal.entities.RequirementApprovalTransition;
import fk.retail.ip.requirement.internal.enums.RequirementApprovalState;
import fk.retail.ip.requirement.internal.repository.RequirementApprovalTransitionRepository;
import fk.retail.ip.requirement.internal.repository.RequirementRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Pragalathan M<pragalathan.m@flipkart.com>
 */

@Slf4j
public class ApprovalService<E extends AbstractEntity> {

    public void changeState(List<E> items,
                            String fromState,
                            String userId,
                            boolean forward,
                            Function<E, String> getter,
                            StageChangeAction<E>... actionListeners) {
        validate(items, fromState, getter);
        for (StageChangeAction<E> consumer : actionListeners) {
            consumer.execute(userId, fromState, forward, items);
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

    public interface StageChangeAction<E extends AbstractEntity> {

        void execute(String userId,
                     String fromState,
                     boolean forward,
                     List<E> entity);
    }

    public static class CopyOnStateChangeAction implements StageChangeAction<Requirement> {

        private RequirementRepository requirementRepository;
        private RequirementApprovalTransitionRepository requirementApprovalStateTransitionRepository;

        public CopyOnStateChangeAction(RequirementRepository requirementRepository, RequirementApprovalTransitionRepository requirementApprovalStateTransitionRepository) {
            this.requirementRepository = requirementRepository;
            this.requirementApprovalStateTransitionRepository = requirementApprovalStateTransitionRepository;
        }


        @Override
        public void execute(String userId, String fromState, boolean forward, List<Requirement> requirements) {
            Map<Long, String> groupToTargetState = getGroupToTargetStateMap(fromState, forward);
            log.info("Constructed map for group to Target state " + groupToTargetState);
            Map<Long, String> requirementToTargetStateMap = getRequirementToTargetStateMap(groupToTargetState, requirements);
            Set<String> fsns = requirements.stream().map(Requirement::getFsn).collect(Collectors.toSet());
            List<Requirement> allEnabledRequirements = requirementRepository.find(fsns, true);
            requirements.stream().forEach((requirement) -> {
                String toState = requirementToTargetStateMap.get(requirement.getId());
                boolean isIPCReviewState = RequirementApprovalState.IPC_REVIEW.toString().equals(toState);
                String cdoState = RequirementApprovalState.CDO_REVIEW.toString();
                Optional<Requirement> toStateEntity = allEnabledRequirements.stream().filter(e -> e.getFsn().equals(requirement.getFsn()) && e.getWarehouse().equals(requirement.getWarehouse()) && e.getState().equals(toState)).findFirst();
                if (forward) {
                    if (toStateEntity.isPresent()) {
                        toStateEntity.get().setQuantity(requirement.getQuantity());
                        if (isIPCReviewState) {
                            Optional<Requirement> cdoStateEntity = allEnabledRequirements.stream().filter(e -> e.getFsn().equals(requirement.getFsn()) && e.getWarehouse().equals(requirement.getWarehouse()) && e.getState().equals(cdoState)).findFirst();
                            toStateEntity.get().setQuantity(cdoStateEntity.get().getQuantity());
                        }
                        toStateEntity.get().setSupplier(requirement.getSupplier());
                        toStateEntity.get().setApp(requirement.getApp());
                        toStateEntity.get().setSla(requirement.getSla());
                        toStateEntity.get().setPreviousStateId(requirement.getId());
                        toStateEntity.get().setCreatedBy(userId);
                        toStateEntity.get().setCurrent(true);
                        requirement.setCurrent(false);
                    } else {
                        Requirement newEntity = new Requirement(requirement);
                        if (isIPCReviewState) {
                            Optional<Requirement> cdoStateEntity = allEnabledRequirements.stream().filter(e -> e.getFsn().equals(requirement.getFsn()) && e.getWarehouse().equals(requirement.getWarehouse()) && e.getState().equals(cdoState)).findFirst();
                            newEntity.setQuantity(cdoStateEntity.get().getQuantity());
                        }
                        newEntity.setState(toState);
                        newEntity.setCreatedBy(userId);
                        newEntity.setPreviousStateId(requirement.getId());
                        newEntity.setCurrent(true);
                        requirementRepository.persist(newEntity);
                        requirement.setCurrent(false);
                    }
                } else {
                    toStateEntity.ifPresent(e -> { // this will always be present
                        e.setCurrent(true);
                        requirement.setCurrent(false);
                    });
                }
            });
            log.info("Updating Projections tables for Requirements");
            requirementRepository.updateProjections(requirements, groupToTargetState);
        }


        private Map<Long, String> getGroupToTargetStateMap(String fromState, boolean forward) {
            List<RequirementApprovalTransition> transitionList = requirementApprovalStateTransitionRepository.getApprovalTransition(fromState, forward);
            return transitionList.stream().collect(Collectors.toMap(RequirementApprovalTransition::getGroupId, RequirementApprovalTransition::getToState));
        }

        private Map<Long,String> getRequirementToTargetStateMap(Map<Long, String> groupToTargetState, List<Requirement> requirements) {
            return requirements.stream().collect(Collectors.toMap(requirement -> requirement.getId(), requirement -> groupToTargetState.get(requirement.getGroup())!= null ? groupToTargetState.get(requirement.getGroup()):groupToTargetState.get(Constants.DEFAULT_TRANSITION_GROUP)));
        }

    }
}

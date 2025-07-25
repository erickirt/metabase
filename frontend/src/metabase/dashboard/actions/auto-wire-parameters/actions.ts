import {
  closeAutoWireParameterToast,
  showAddedCardAutoWireParametersToast,
  showAutoWireParametersToast,
} from "metabase/dashboard/actions/auto-wire-parameters/toasts";
import {
  getAllDashboardCardsWithUnmappedParameters,
  getAutoWiredMappingsForDashcards,
  getMatchingParameterOption,
  getParameterMappings,
} from "metabase/dashboard/actions/auto-wire-parameters/utils";
import { getExistingDashCards } from "metabase/dashboard/actions/utils";
import {
  getDashCardById,
  getDashboard,
  getDashboardHeaderParameters,
  getParameters,
  getQuestions,
  getSelectedTabId,
  getTabs,
} from "metabase/dashboard/selectors";
import {
  findDashCardForInlineParameter,
  isQuestionDashCard,
} from "metabase/dashboard/utils";
import {
  getMappingOptionByTarget,
  getParameterMappingOptions,
} from "metabase/parameters/utils/mapping-options";
import type {
  DashCardId,
  DashboardParameterMapping,
  DashboardTabId,
  ParameterId,
  ParameterTarget,
  QuestionDashboardCard,
} from "metabase-types/api";
import type { Dispatch, GetState, StoreDashcard } from "metabase-types/store";

export function showAutoWireToast(
  parameter_id: ParameterId,
  dashcard: QuestionDashboardCard,
  target: ParameterTarget,
  selectedTabId: DashboardTabId,
) {
  return function (dispatch: Dispatch, getState: GetState) {
    const dashboardState = getState().dashboard;
    const questions = getQuestions(getState());
    const parameter = getParameters(getState()).find(
      ({ id }) => id === parameter_id,
    );

    if (!dashboardState.dashboardId || !parameter) {
      return;
    }

    const dashcardsToAutoApply = getAllDashboardCardsWithUnmappedParameters({
      dashboards: dashboardState.dashboards,
      dashcards: dashboardState.dashcards,
      dashboardId: dashboardState.dashboardId,
      parameterId: parameter_id,
      selectedTabId,
      // exclude current dashcard as it's being updated in another action
      excludeDashcardIds: [dashcard.id],
    });

    const dashcards = Object.values(dashboardState.dashcards);

    const dashcardAttributes = getAutoWiredMappingsForDashcards(
      parameter,
      dashcardsToAutoApply,
      target,
      questions,
      dashcards,
    );

    const shouldShowToast = dashcardAttributes.length > 0;

    if (!shouldShowToast) {
      return;
    }

    const originalDashcardAttributes = dashcardsToAutoApply.map((dashcard) => ({
      id: dashcard.id,
      attributes: {
        parameter_mappings: dashcard.parameter_mappings,
      },
    }));

    const mappingOption = getMatchingParameterOption(
      parameter,
      dashcard,
      target,
      questions,
      dashcards,
    );

    const tabs = getTabs(getState());

    if (!mappingOption) {
      return;
    }

    dispatch(
      showAutoWireParametersToast({
        dashcardAttributes,
        originalDashcardAttributes,
        columnName: formatMappingOption(mappingOption),
        hasMultipleTabs: tabs.length > 1,
        parameterId: parameter_id,
      }),
    );
  };
}

export function showAutoWireToastNewCard({
  dashcard_id,
}: {
  dashcard_id: DashCardId;
}) {
  return function (dispatch: Dispatch, getState: GetState) {
    dispatch(closeAutoWireParameterToast());

    const dashboardState = getState().dashboard;
    const dashboardId = dashboardState.dashboardId;

    if (!dashboardId) {
      return;
    }

    const dashboard = getDashboard(getState());
    if (!dashboard || !dashboard.parameters) {
      return;
    }

    const questions = getQuestions(getState());
    const selectedTabId = getSelectedTabId(getState());

    // Inline dashcard parameters should not be used for auto-wiring
    const parameters = getDashboardHeaderParameters(getState());

    const dashcards = getExistingDashCards(
      dashboardState.dashboards,
      dashboardState.dashcards,
      dashboardId,
      selectedTabId,
    );

    const targetDashcard: StoreDashcard = getDashCardById(
      getState(),
      dashcard_id,
    );

    if (!targetDashcard || !isQuestionDashCard(targetDashcard)) {
      return;
    }

    const targetQuestion = questions[targetDashcard.card.id];

    const parametersMappingsToApply: DashboardParameterMapping[] = [];
    const processedParameterIds = new Set();

    for (const parameter of parameters) {
      const parameterDashcard = findDashCardForInlineParameter(
        parameter.id,
        Object.values(dashcards),
      );

      const dashcardMappingOptions = getParameterMappingOptions(
        targetQuestion,
        parameter,
        targetDashcard.card,
        targetDashcard,
        parameterDashcard,
      );

      for (const dashcard of dashcards) {
        const mappings = (dashcard.parameter_mappings ?? []).filter(
          (mapping) => mapping.parameter_id === parameter.id,
        );

        for (const mapping of mappings) {
          const option = getMappingOptionByTarget(
            dashcardMappingOptions,
            mapping.target,
            targetQuestion,
            parameter,
          );

          if (
            option &&
            targetDashcard.card_id &&
            !processedParameterIds.has(parameter.id)
          ) {
            parametersMappingsToApply.push(
              ...getParameterMappings(
                targetDashcard,
                parameter.id,
                targetDashcard.card_id,
                option.target,
              ),
            );
            processedParameterIds.add(parameter.id);
          }
        }
      }
    }

    if (parametersMappingsToApply.length === 0) {
      return;
    }

    const parametersToMap = dashboard.parameters.filter((p) =>
      processedParameterIds.has(p.id),
    );

    dispatch(
      showAddedCardAutoWireParametersToast({
        targetDashcard,
        dashcard_id,
        parametersMappingsToApply,
        parametersToMap,
      }),
    );
  };
}

function formatMappingOption({
  name,
  sectionName,
}: {
  name: string;
  sectionName?: string;
}) {
  if (sectionName == null) {
    // for native question variables or field literals we just display the name
    return name;
  }
  return `${sectionName}.${name}`;
}

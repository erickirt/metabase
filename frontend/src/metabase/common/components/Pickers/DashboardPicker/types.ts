import type {
  CollectionId,
  DashboardId,
  ListCollectionItemsRequest,
  SearchModel,
} from "metabase-types/api";

import type {
  EntityPickerModalOptions,
  ListProps,
  PickerState,
} from "../../EntityPicker";
import type {
  CollectionItemId,
  CollectionPickerItem,
} from "../CollectionPicker";

export type DashboardPickerModel = Extract<
  CollectionPickerItem["model"],
  "dashboard" | "collection"
>;
export type DashboardPickerValueModel = Extract<
  CollectionPickerItem["model"],
  "dashboard"
>;

export type DashboardPickerValueItem = CollectionPickerItem & {
  id: DashboardId;
  model: DashboardPickerValueModel;
};

export type DashboardPickerInitialValueItem = {
  id: DashboardId | CollectionId;
  model: DashboardPickerModel;
};

// we could tighten this up in the future, but there's relatively little value to it
export type DashboardPickerItem = CollectionPickerItem;

export type DashboardPickerOptions = EntityPickerModalOptions & {
  allowCreateNew?: boolean;
  showPersonalCollections?: boolean;
  showRootCollection?: boolean;
};

export type DashboardItemListProps = ListProps<
  CollectionItemId,
  SearchModel,
  DashboardPickerItem,
  ListCollectionItemsRequest,
  DashboardPickerOptions
>;

export type DashboardPickerStatePath = PickerState<
  DashboardPickerItem,
  ListCollectionItemsRequest
>;

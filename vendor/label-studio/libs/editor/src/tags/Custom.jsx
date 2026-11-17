/** Placeholder for CustomInterface tag to display Enterprise-only warning. **/
/** Tag is renamed to ReactCode so we support both names. **/

import { types } from "mobx-state-tree";
import { observer } from "mobx-react";
import { EnterpriseBadge } from "@humansignal/ui";
import Registry from "../core/Registry";
import ControlBase from "./control/Base";
import { getDocsUrl } from "../utils/docs";

const ENTERPRISE_URL = getDocsUrl("supported-controls");

const CustomInterfaceModel = types.compose(
  "CustomInterfaceModel",
  ControlBase,
  types.model({
    type: "custominterface",
  }),
);

const Code = ({ children }) => (
  <code className="text-sm font-mono bg-neutral-surface border border-neutral-border rounded px-tighter py-tightest">
    {children}
  </code>
);

// Register custom tag placeholder for opensource
if (!APP_SETTINGS?.billing?.enterprise && !Registry.models.custominterface) {
  const CustomComponentWrapper = observer(({ item }) => {
    return (
      <div className="py-base">
        <EnterpriseBadge /> <Code>{item.type === "custominterface" ? "CustomInterface" : "React"}</Code> tag is only
        available in{" "}
        <a className="no-go" href={ENTERPRISE_URL} target="_blank" rel="noreferrer">
          Enterprise
        </a>
        .
      </div>
    );
  });

  Registry.addTag("custominterface", CustomInterfaceModel, CustomComponentWrapper);
  Registry.addObjectType(CustomInterfaceModel);
}

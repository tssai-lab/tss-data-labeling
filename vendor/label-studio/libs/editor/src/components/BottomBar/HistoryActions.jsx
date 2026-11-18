import { observer } from "mobx-react";
import { IconRedo, IconReset, IconUndo } from "@humansignal/icons";
import { Tooltip, Button } from "@humansignal/ui";
import { cn } from "../../utils/bem";
import "./HistoryActions.scss";

export const EditingHistory = observer(({ entity }) => {
  const { history } = entity;

  return (
    <div className={cn("history-buttons").toClassName()}>
      <Tooltip title="撤销">
        <Button
          variant="neutral"
          size="small"
          aria-label="撤销"
          look="string"
          disabled={!history?.canUndo}
          onClick={() => entity.undo()}
          className="aspect-square"
          leading={<IconUndo />}
          data-testid="bottombar-undo-button"
        />
      </Tooltip>
      <Tooltip title="重做">
        <Button
          variant="neutral"
          size="small"
          look="string"
          aria-label="重做"
          disabled={!history?.canRedo}
          onClick={() => entity.redo()}
          className="aspect-square"
          leading={<IconRedo />}
          data-testid="bottombar-redo-button"
        />
      </Tooltip>
      <Tooltip title="重置">
        <Button
          variant="negative"
          look="string"
          size="small"
          aria-label="重置"
          disabled={!history?.canUndo}
          onClick={() => history?.reset()}
          className="aspect-square"
          leading={<IconReset />}
          data-testid="bottombar-reset-button"
        />
      </Tooltip>
    </div>
  );
});

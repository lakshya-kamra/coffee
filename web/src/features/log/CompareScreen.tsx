/**
 * STUB from V4-WSHELL so the `compare` route (/log/:uid/compare/:other, v4 §3.8) resolves
 * through the registry. V4-WLOG replaces this file with the real two-brew table.
 */
import { usePane } from "@/app/panes";
import { nav } from "@/app/router";
import { DashedPlaceholder, DetailTopBar } from "@/design/components";

export function CompareScreen({ brewUid, otherUid }: { brewUid: string; otherUid: string }) {
  const pane = usePane();
  return (
    <div className="column">
      {!pane.inPane && <DetailTopBar onBack={nav.back} />}
      <h1 className="t-headline-l" tabIndex={-1}>
        Compare brews
      </h1>
      <DashedPlaceholder text={`${brewUid} and ${otherUid}`} height={160} />
    </div>
  );
}

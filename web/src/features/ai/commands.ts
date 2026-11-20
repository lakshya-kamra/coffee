/**
 * Palette commands from the AI feature (v4 §2.8). Registered when this feature first loads (Log,
 * Insights, Review or Gear), and kept: the command is always true while the app runs.
 */
import { registerCommands } from "@/app/palette/commands";
import { nav } from "@/app/router";

let registered = false;

export function registerAiCommands(): void {
  if (registered) return;
  registered = true;
  registerCommands([
    {
      id: "ai.setup",
      title: "Set up AI analysis",
      subtitle: "Gear › Settings › AI analysis",
      group: "Actions",
      icon: "Tune",
      keywords: ["ai", "key", "model", "openai", "anthropic", "gemini", "openrouter", "analysis"],
      run: () => nav.switchTab("gear", "app"),
    },
  ]);
}

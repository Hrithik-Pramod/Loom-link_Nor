#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════
# LOOM LINK — HM90 Sovereign Node Model Setup
# Run this on the HM90 (100.64.132.128) to install the recommended models
# ═══════════════════════════════════════════════════════════════════════
#
# Your HM90 Ryzen 9 has plenty of power for 7B-14B models.
# The 3B Llama is too small for reliable ISO 14224 classification.
#
# MODEL RECOMMENDATIONS (ranked by accuracy for our use case):
#
# 1. Qwen 2.5 7B (BEST CHOICE)
#    - Best structured JSON output of any 7B model
#    - Excellent instruction following
#    - ~5GB RAM, fast inference on Ryzen 9
#    - Consistently produces well-calibrated confidence scores
#
# 2. Mistral 7B v0.3
#    - Proven reliable for structured classification tasks
#    - Very consistent JSON formatting
#    - ~5GB RAM
#    - Community favorite for API-driven classification
#
# 3. Llama 3.1 8B
#    - Stronger reasoning than Llama 3.2 3B
#    - Good JSON output with few-shot prompting
#    - ~5.5GB RAM
#    - Most downloaded model on Ollama for good reason
#
# 4. Phi-4 Mini (3.8B) — If RAM is very tight
#    - Outperforms many 7B models on structured tasks
#    - Only ~2.5GB RAM
#    - Microsoft's best small model
#
# ═══════════════════════════════════════════════════════════════════════

set -e

echo "═══════════════════════════════════════════════════════════"
echo "  LOOM LINK — Installing models on HM90 Sovereign Node"
echo "═══════════════════════════════════════════════════════════"

# Check Ollama is running
if ! command -v ollama &> /dev/null; then
    echo "ERROR: Ollama is not installed. Install from https://ollama.com"
    exit 1
fi

echo ""
echo "Current models:"
ollama list
echo ""

# ── Option 1: Qwen 2.5 7B (RECOMMENDED) ─────────────────────────
echo "═══════════════════════════════════════════════════════════"
echo "  Installing Qwen 2.5 7B (recommended for classification)"
echo "═══════════════════════════════════════════════════════════"
ollama pull qwen2.5:7b

# ── Option 2: Mistral 7B (backup) ────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Installing Mistral 7B (backup model)"
echo "═══════════════════════════════════════════════════════════"
ollama pull mistral:7b

# ── Option 3: Llama 3.1 8B (backup) ─────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Installing Llama 3.1 8B (backup model)"
echo "═══════════════════════════════════════════════════════════"
ollama pull llama3.1:8b

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  All models installed. Updated model list:"
echo "═══════════════════════════════════════════════════════════"
ollama list

echo ""
echo "To use Qwen 2.5 in Loom Link, update application.yml:"
echo "  loomlink.llm.model-id: qwen2.5:7b"
echo ""
echo "To test immediately:"
echo '  ollama run qwen2.5:7b "Classify: Pump grinding noise, bearing temp 82C"'
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Setup complete. HM90 Sovereign Node ready."
echo "═══════════════════════════════════════════════════════════"

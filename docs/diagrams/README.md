# Diagram sources

Three Mermaid files describing the service. They render natively on GitHub
and in VS Code; the same source imports cleanly into LucidChart.

| file                                                | purpose                                                       |
| --------------------------------------------------- | ------------------------------------------------------------- |
| [`architecture.mmd`](architecture.mmd)              | Component view — who calls whom across packages               |
| [`sequence-happy.mmd`](sequence-happy.mmd)          | Step-by-step happy path through one scheduled run             |
| [`sequence-failure.mmd`](sequence-failure.mmd)      | The three failure modes and what they do to the watermark     |

## Importing into LucidChart

LucidChart added a native Mermaid importer in 2023:

1. Open or create a LucidChart document.
2. **File → Import → Mermaid** (under "Diagrams").
3. Paste the *contents* of the `.mmd` file. Click **Import**.
4. The diagram appears as native LucidChart shapes — fully editable.

Alternative if your LucidChart account doesn't expose that menu:

1. Open <https://mermaid.live> and paste the `.mmd` content.
2. Export as **SVG** or **PNG**.
3. In LucidChart use **File → Import → Image** (SVG keeps shapes editable
   on Pro plans; PNG is rasterised).

## Editing

These files are the source of truth. Update the `.mmd` and the matching
`code-walkthrough.md` block together — keeping them in sync is intentional.

## Quick local preview

```bash
# VS Code
code --install-extension bierner.markdown-mermaid

# Or render to PNG via the Mermaid CLI
npm install -g @mermaid-js/mermaid-cli
mmdc -i architecture.mmd -o architecture.png
```

# AGENTS.md

`.omp/AGENTS.md` is the canonical source for repository constraints and agent workflow. OMP loads it automatically; other coding agents should read it before making changes.

Load only task-relevant references from `.docs/agents/` rather than the entire directory. To delegate work to a separate agent, read `.docs/agents/paseo-delegation.md`: prefer OMP agents started with the Paseo CLI. Role-specific configuration lives in `.opencode/agents/`. Do not duplicate the canonical instructions here.

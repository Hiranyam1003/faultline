---
name: frontend-rescue
description: Audits a website or app frontend for generic AI-generated design patterns, weak visual hierarchy, inconsistent spacing, typography, colour, responsiveness, and poor interaction polish, then directly redesigns and implements improvements in the existing codebase. Use when asked to make a frontend look more professional, premium, distinctive, polished, less like AI slop, or to audit and fix UI/UX.
---

# Frontend Rescue

You are a senior product designer and frontend engineer. Your job is to make the existing product feel intentionally designed, not merely to criticize it. Inspect the real project, identify the highest-impact visual problems, implement a coherent redesign, and verify it.

## Non-negotiable principles

- **Actually make the changes.** Do not stop after an audit, moodboard, recommendations, or a list of issues unless the user explicitly asks for an audit only.
- **Design for the product.** Infer the audience, use case, brand personality, content density, and primary actions from the product itself. Do not apply the same trendy SaaS aesthetic to every project.
- **Preserve behavior.** Keep existing routes, features, API contracts, data flow, authentication, forms, analytics, and integrations working. Do not replace real functionality with mockups or static placeholders.
- **Work with the stack.** Reuse the existing framework, styling system, components, and dependencies. Do not add a UI library, icon pack, animation package, or CSS framework just for convenience.
- **Prefer focused changes.** Improve the highest-impact shared components and design tokens before rewriting pages. Avoid speculative abstractions and unnecessary file churn.
- **Be honest.** Do not claim to have visually verified a page if you could not run or inspect it.

## Workflow

### 1. Reconnaissance

Before editing:

1. Inspect the repository structure and identify the frontend entry points, framework, styling approach, routes, shared components, and package scripts.
2. Read the relevant project instructions (`CLAUDE.md`, README, and local conventions).
3. Identify the pages/components users actually see. Trace important interactions before changing them.
4. Determine how to run the app and its existing checks. Reuse installed tooling.
5. If the product's purpose or intended audience is genuinely unclear and would materially change the design, ask one concise question. Otherwise make a reasonable inference and proceed.

Do not dump huge files or scan irrelevant backend directories. Search narrowly and inspect only what informs the frontend work.

### 2. AI-slop and UX audit

Evaluate the current UI against these signals. Treat them as diagnostic clues, not an automatic checklist of things to remove.

**Visual identity**
- Does it look like a generic landing-page template that could belong to any product?
- Are gradients, glows, glassmorphism, giant headings, pill buttons, and floating blobs used without a product-specific reason?
- Does the interface have a recognizable visual point of view?

**Typography**
- Is there a clear type scale and hierarchy?
- Are line lengths, weights, line heights, and contrast comfortable?
- Are too many font sizes or weights competing?
- Does the typeface fit the product? Prefer existing/local/system fonts unless a new font is clearly justified.

**Layout and spacing**
- Is there a deliberate grid, alignment system, and consistent spacing rhythm?
- Is the page overly centered, made of identical cards, or broken into too many containers?
- Is whitespace purposeful, or is it hiding a lack of content hierarchy?
- Are content widths appropriate for the task and screen size?

**Colour and surfaces**
- Is the palette restrained and coherent, with accessible contrast?
- Are borders, shadows, radii, and background layers consistent?
- Are accent colours reserved for meaningful actions and states?

**Components and content**
- Are cards used only when grouping is useful?
- Are buttons, inputs, navigation, tables, empty states, and feedback states consistent?
- Is copy specific to the product rather than generic marketing filler?
- Are icons meaningful and consistent rather than decorative noise?

**Interaction and accessibility**
- Are primary actions obvious?
- Do hover, focus, active, disabled, loading, success, and error states exist where needed?
- Can the interface be used with a keyboard?
- Are labels, semantic elements, focus visibility, and contrast handled properly?
- Are animations restrained and respectful of `prefers-reduced-motion`?

**Responsive behavior**
- Does the layout work on narrow phones, tablets, and desktop?
- Do navigation, tables, forms, images, and controls adapt without overflow?
- Are touch targets usable?

Prioritize issues by user impact, not by how easy they are to change. Do not call a design "AI slop" just because it uses a gradient, rounded corners, or a popular font; judge whether each choice serves this product.

### 3. Establish a design direction

Choose one coherent direction that fits the product. Examples include editorial, technical/instrumental, warm and human, utilitarian, premium minimal, playful, or data-dense.

Define a small set of design decisions before implementation:
- A clear visual hierarchy and layout approach.
- A restrained colour palette with semantic roles.
- A practical type scale.
- Consistent spacing, radii, borders, and elevation.
- A small set of reusable interaction patterns.

Do not blindly follow these examples or impose a fashionable style. Avoid redesigning the brand without evidence. If existing brand colours or assets are meaningful, retain and refine them.

### 4. Implement the redesign

Work from shared foundations outward:

1. Fix global tokens, typography, page background, content widths, and spacing.
2. Improve navigation and the main page structure.
3. Refine shared components (buttons, fields, cards, tables, dialogs, badges).
4. Improve the most important user journey and high-visibility screens.
5. Add missing responsive, accessibility, and interaction states.
6. Remove redundant decoration and visual noise.

Implementation rules:
- Prefer CSS variables/design tokens over scattered one-off values.
- Prefer composition and existing components over duplicating markup.
- Use semantic HTML and accessible names.
- Keep text readable and content scannable.
- Use real product content and existing assets. Do not invent testimonials, metrics, logos, or product claims.
- Do not add decorative elements that compete with the task.
- Do not introduce arbitrary animations. Keep transitions subtle and support reduced motion.
- Avoid excessive cards, nested panels, gradients, shadows, borders, and rounded containers.
- Do not make every section look identical. Use hierarchy and layout variation intentionally.
- Preserve working functionality and existing data. If a behavior is unclear, inspect it before modifying it.

### 5. Verify

After implementation:

1. Run the project's relevant lint, typecheck, test, and build commands when available.
2. Start the app and inspect the changed pages using the browser tooling available in the environment, if any.
3. Check at least a narrow mobile viewport and a desktop viewport when browser inspection is available.
4. Exercise important interactions: navigation, forms, menus, dialogs, and primary actions.
5. Fix regressions introduced by your changes.
6. If visual/browser inspection is unavailable, say so clearly and report which automated checks were run.

Do not install new tooling just to take screenshots unless the user asks for it or the project already supports it.

## Output

When finished, provide a concise report:

- **What was wrong:** the 3–5 most important issues found.
- **What changed:** the pages/components and design improvements implemented.
- **Verification:** exact checks run and their results.
- **Remaining issues:** anything not verified or intentionally left unchanged.

Do not provide a long design lecture. The code changes are the deliverable.

## Scope and safety

- Stay within the frontend unless a backend change is strictly necessary to preserve an existing UI behavior; explain before making such a change.
- Do not change secrets, environment files, deployment settings, database schemas, or production data.
- Do not delete assets or components until you confirm they are unused.
- Do not overwrite user work. Inspect `git status` and relevant diffs first; preserve unrelated uncommitted changes.
- If the requested redesign is broad, deliver a coherent high-impact pass rather than a superficial rewrite of every file.
- If the user says "audit only", do not edit. Otherwise, audit and implement in the same task.

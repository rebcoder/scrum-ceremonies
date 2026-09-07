# Demo Mode Screenshots

This directory contains static screenshots used for Demo Mode walkthroughs.

## Structure

```
assets/demo/
├── poker/
│   ├── poker-step-1.png  # Create room screen
│   ├── poker-step-2.png  # Team joins screen
│   ├── poker-step-3.png  # Voting screen
│   └── poker-step-4.png  # Results revealed screen
├── retro/
│   ├── retro-step-1.png  # Create board screen
│   ├── retro-step-2.png  # Adding cards screen
│   ├── retro-step-3.png  # Upvoting cards screen
│   └── retro-step-4.png  # Final board view
└── mood/
    ├── mood-step-1.png   # Create mood room screen
    ├── mood-step-2.png   # Team joins screen
    ├── mood-step-3.png   # Mood selection screen
    └── mood-step-4.png   # Results revealed screen
```

## Screenshot Requirements

- **Format**: PNG or JPG
- **Recommended size**: 1200x800px (or maintain aspect ratio of actual screens)
- **Quality**: High resolution, clear text
- **Content**: Should show the actual UI state for each step
- **Naming**: Follow the pattern `{tool}-step-{number}.png`

## How to Generate Screenshots

With the local stack running (`./scripts/dev-up.sh`):

```bash
node scripts/capture-screenshots.js            # all three tools + docs/images/home*.png
ONLY=poker node scripts/capture-screenshots.js # one tool
```

The script drives the real UI with three participants (Alice, Bob, Chloe) and
overwrites the files in this directory, so the walkthrough always shows the
current design.

## Notes

- Screenshots should be representative of the actual user experience
- Use demo names only, never real people
- Regenerate them whenever the UI changes

/**
 * ============================================
 * DEMO MODE CONTROLLER
 * ============================================
 * 
 * CRITICAL: This is a FRONTEND-ONLY demo mode.
 * - NO backend API calls
 * - NO WebSocket connections
 * - NO real room creation
 * - NO data persistence
 * 
 * Demo Mode uses static screenshots and step-by-step walkthroughs
 * to explain how each tool works without affecting production flows.
 */

// Demo configuration with step-by-step walkthroughs
const demoConfig = {
  poker: {
    title: "Planning Poker",
    steps: [
      {
        image: "assets/demo/poker/poker-step-1.png",
        text: "Create a room and share the link with your team."
      },
      {
        image: "assets/demo/poker/poker-step-2.png",
        text: "Team members join instantly without login—just enter a name."
      },
      {
        image: "assets/demo/poker/poker-step-3.png",
        text: "Everyone votes simultaneously by clicking a card value."
      },
      {
        image: "assets/demo/poker/poker-step-4.png",
        text: "Reveal all votes together to see consensus or discussion points."
      }
    ]
  },
  retro: {
    title: "Sprint Retrospective",
    steps: [
      {
        image: "assets/demo/retro/retro-step-1.png",
        text: "Create a retrospective board and share the room code."
      },
      {
        image: "assets/demo/retro/retro-step-2.png",
        text: "Team joins and adds cards to columns: What Went Well, What Could Improve, Action Items."
      },
      {
        image: "assets/demo/retro/retro-step-3.png",
        text: "Upvote cards to prioritize feedback and organize discussions."
      },
      {
        image: "assets/demo/retro/retro-step-4.png",
        text: "Review all feedback organized by category for team improvement."
      }
    ]
  },
  mood: {
    title: "Team Mood Check",
    steps: [
      {
        image: "assets/demo/mood/mood-step-1.png",
        text: "Create a mood room and share the link with your team."
      },
      {
        image: "assets/demo/mood/mood-step-2.png",
        text: "Team members join and see the current question."
      },
      {
        image: "assets/demo/mood/mood-step-3.png",
        text: "Each member anonymously selects their mood (😀, 😐, 😞, etc.)."
      },
      {
        image: "assets/demo/mood/mood-step-4.png",
        text: "Reveal aggregated results to see team health at a glance."
      }
    ]
  }
};

// Demo state management
let currentTool = null;
let currentStep = 0;
let demoState = {
  tool: null,
  step: 0
};

/**
 * Initialize demo mode
 */
function initDemo() {
  // Check if we're returning to a specific demo
  const urlParams = new URLSearchParams(window.location.search);
  const tool = urlParams.get('tool');
  const step = parseInt(urlParams.get('step') || '0');
  
  if (tool && demoConfig[tool]) {
    startDemo(tool, step);
  } else {
    showToolSelection();
  }
  
  // Handle page refresh - restart demo cleanly
  window.addEventListener('beforeunload', () => {
    // Clear any demo state
    sessionStorage.removeItem('demoState');
  });
}

/**
 * Show tool selection screen
 */
function showToolSelection() {
  document.getElementById('tool-selection-screen').style.display = 'block';
  document.getElementById('demo-walkthrough-screen').style.display = 'none';
  currentTool = null;
  currentStep = 0;
}

/**
 * Start demo for a specific tool
 * @param {string} tool - Tool name (poker, retro, mood)
 * @param {number} step - Starting step (default: 0)
 */
function startDemo(tool, step = 0) {
  if (!demoConfig[tool]) {
    console.error('Invalid tool:', tool);
    return;
  }
  
  currentTool = tool;
  currentStep = step;
  demoState.tool = tool;
  demoState.step = step;
  
  // Update URL without reload
  window.history.pushState({ tool, step }, '', `?tool=${tool}&step=${step}`);
  
  // Show walkthrough screen
  document.getElementById('tool-selection-screen').style.display = 'none';
  document.getElementById('demo-walkthrough-screen').style.display = 'block';
  
  // Update UI
  updateDemoUI();
}

/**
 * Update demo UI with current step
 */
function updateDemoUI() {
  if (!currentTool || !demoConfig[currentTool]) return;
  
  const config = demoConfig[currentTool];
  const step = config.steps[currentStep];
  
  if (!step) return;
  
  // Update title
  document.getElementById('demo-tool-title').textContent = config.title;
  
  // Update step counter
  document.getElementById('current-step').textContent = currentStep + 1;
  document.getElementById('total-steps').textContent = config.steps.length;
  
  // Update image
  const img = document.getElementById('demo-image');
  img.src = step.image;
  img.alt = `Demo step ${currentStep + 1} - ${step.text}`;
  
  // Handle image load errors (for missing screenshots)
  img.onerror = function() {
    this.style.display = 'none';
    const container = this.parentElement;
    if (!container.querySelector('.demo-placeholder')) {
      const placeholder = document.createElement('div');
      placeholder.className = 'demo-placeholder';
      placeholder.innerHTML = `
        <div class="demo-placeholder-icon ic" data-ic="camera" aria-hidden="true"></div>
        <div class="demo-placeholder-text">Screenshot placeholder</div>
        <div class="demo-placeholder-note">Screenshot: ${step.image.split('/').pop()}</div>
      `;
      container.appendChild(placeholder);
      if (typeof window.hydrateIcons === 'function') window.hydrateIcons(placeholder);
    }
  };
  
  img.onload = function() {
    this.style.display = 'block';
    const placeholder = this.parentElement.querySelector('.demo-placeholder');
    if (placeholder) {
      placeholder.remove();
    }
  };
  
  // Update text
  document.getElementById('demo-step-text').textContent = step.text;
  
  // Hide highlight overlay (removed - not aligning well with screenshots)
  document.getElementById('demo-highlight').style.display = 'none';
  
  // Update navigation buttons
  document.getElementById('demo-prev-btn').disabled = currentStep === 0;
  document.getElementById('demo-next-btn').disabled = currentStep === config.steps.length - 1;
  
  // Update next button text on last step
  const nextBtn = document.getElementById('demo-next-btn');
  if (currentStep === config.steps.length - 1) {
    nextBtn.textContent = 'Finish';
  } else {
    nextBtn.textContent = 'Next →';
  }
}

/**
 * Navigate to next step
 */
function nextStep() {
  if (!currentTool || !demoConfig[currentTool]) return;
  
  const config = demoConfig[currentTool];
  
  if (currentStep < config.steps.length - 1) {
    currentStep++;
    demoState.step = currentStep;
    updateDemoUI();
    window.history.pushState(
      { tool: currentTool, step: currentStep },
      '',
      `?tool=${currentTool}&step=${currentStep}`
    );
  } else {
    // Finished - return to tool selection
    showToolSelection();
  }
}

/**
 * Navigate to previous step
 */
function previousStep() {
  if (!currentTool) return;
  
  if (currentStep > 0) {
    currentStep--;
    demoState.step = currentStep;
    updateDemoUI();
    window.history.pushState(
      { tool: currentTool, step: currentStep },
      '',
      `?tool=${currentTool}&step=${currentStep}`
    );
  }
}

/**
 * Go back to tool selection
 */
function goBack() {
  showToolSelection();
  window.history.pushState({}, '', 'demo.html');
}

/**
 * Exit demo and return to homepage
 */
function exitDemo() {
  // Clear any demo state
  sessionStorage.removeItem('demoState');
  // Navigate to homepage
  window.location.href = '/';
}

/**
 * Handle browser back/forward buttons
 */
window.addEventListener('popstate', (event) => {
  if (event.state && event.state.tool) {
    startDemo(event.state.tool, event.state.step || 0);
  } else {
    showToolSelection();
  }
});

// Initialize demo when page loads
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initDemo);
} else {
  initDemo();
}

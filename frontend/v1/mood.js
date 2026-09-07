/**
 * Copyright (c) 2026 rebcoder. MIT licensed — see LICENSE.
 * 
 * Team Mood Check - JavaScript Logic
 * Handles mode selection, question flow, response capture, and result aggregation
 */

// State management
let moodStompClient = null;
let userId = null;
let userName = null;
let currentRoomId = null;
let currentMode = null; // 'quick' or 'scrum'
let currentQuestion = 0;
let responses = {};
let allResponses = []; // Aggregated responses from all users
let hasSubmitted = false;
let isHost = false;

// Team name utilities loaded from shared.js
// Aliases for backward compat within this file
var moodGetStoredTeamName = getStoredTeamName;
var moodEnsureTeamNameAssigned = ensureTeamNameAssigned;

function moodUpdateTeamNameUI() {
  const teamNameEl = document.getElementById('mood-team-name');
  if (!teamNameEl) return;
  teamNameEl.textContent = moodGetStoredTeamName() || 'Team';
}

// NEW: PDF Export state - stores data needed for PDF generation
let pdfExportData = {
  results: null,      // Aggregated results from backend
  hostName: null,     // Host name
  participantNames: [], // List of participant names
  mode: null,         // 'QUICK_PULSE' or 'SCRUM_PULSE'
  roomId: null,       // Room ID for filename
  date: null          // Session date/time
};

// Aria-label mappings for accessibility
const MOOD_ARIA_LABELS = {
  '😞': 'Very unhappy',
  '😐': 'Neutral',
  '🙂': 'Happy',
  '😄': 'Very happy',
  '🚀': 'Excellent'
};

const CONFIDENCE_ARIA_LABELS = {
  1: 'Very low confidence',
  2: 'Low confidence',
  3: 'Moderate confidence',
  4: 'High confidence',
  5: 'Very high confidence'
};

// Question definitions for Scrum Pulse mode
const scrumPulseQuestions = [
  {
    id: 'mood',
    title: 'How are you feeling right now?',
    type: 'mood',
    options: ['😞', '😐', '🙂', '😄', '🚀']
  },
  {
    id: 'confidence',
    title: 'How confident are you about this sprint?',
    type: 'confidence',
    options: [1, 2, 3, 4, 5]
  },
  {
    id: 'workload',
    title: 'How is your current workload?',
    type: 'workload',
    options: ['Too low', 'Balanced', 'Too high']
  },
  {
    id: 'blocked',
    title: 'Are you currently blocked?',
    type: 'blocked',
    options: ['Yes', 'No']
  },
  {
    id: 'comment',
    title: 'Any additional comments? (Optional)',
    type: 'comment',
    optional: true
  }
];

// Quick Pulse question
const quickPulseQuestion = {
  id: 'mood',
  title: 'How are you feeling right now?',
  type: 'mood',
  options: ['😞', '😐', '🙂', '😄', '🚀']
};

/**
 * Show toast notification
 */
function showToast(message, type = 'info', duration = 3000) {
  const container = document.getElementById('toast-container') || document.body;
  const toast = document.createElement('div');
  toast.className = `toast-enhanced toast-${type}`;
  toast.innerHTML = `
    <div class="toast-icon ic" data-ic="${{
      success: 'check-circle',
      error: 'circle-x',
      warning: 'alert-triangle',
      info: 'info'
    }[type] || 'info'}" aria-hidden="true"></div>
    <div class="toast-content">${message}</div>
    <button class="toast-close" aria-label="Close notification"><span class="ic" data-ic="x" aria-hidden="true"></span></button>
  `;
  container.appendChild(toast);
  if (typeof window.hydrateIcons === 'function') window.hydrateIcons(toast);
  
  const closeBtn = toast.querySelector('.toast-close');
  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      removeToast(toast);
    });
  }
  
  setTimeout(() => {
    removeToast(toast);
  }, duration);
  
  return toast;
}

function removeToast(toast) {
  if (toast && toast.parentNode) {
    toast.classList.add('toast-exit');
    setTimeout(() => {
      if (toast && toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 300);
  }
}

/**
 * Generate random user ID
 */
function generateUserId() {
  return Math.random().toString(36).substr(2, 9);
}

/**
 * Get room ID from URL
 */
function getRoomIdFromUrl() {
  return new URLSearchParams(window.location.search).get('room');
}

/**
 * Initialize event listeners
 */
function initializeEventListeners() {
  // Create room button - this now just shows mode selection
  const createBtn = document.getElementById('create-room-btn');
  if (createBtn) {
    createBtn.addEventListener('click', () => {
      const nameInput = document.getElementById('mood-name-input');
      const name = nameInput ? (nameInput.value || '').trim() : '';
      
      // Name is mandatory for room creation
      if (!name) {
        showToast('Please enter your name to create a room', 'error');
        if (nameInput) nameInput.focus();
        return;
      }
      
      if (name.length > 20) {
        showToast('Name must be 20 characters or less', 'error');
        if (nameInput) nameInput.focus();
        return;
      }
      
      // Save name to sessionStorage
      sessionStorage.setItem('moodUserName', name);
      
      // Hide room creation section and show mode selection
      const roomSection = document.getElementById('room-section');
      if (roomSection) {
        roomSection.style.display = 'none';
      }
      
      // Hide "How to Use" section when room is created
      const rulesSection = document.getElementById('mood-rules-section');
      if (rulesSection) {
        rulesSection.style.display = 'none';
      }
      
      // Show mode selection
      showModeSelection();
    });
  }

  // Join room button
  const joinBtn = document.getElementById('join-room-btn');
  if (joinBtn) {
    joinBtn.addEventListener('click', () => {
      const roomId = document.getElementById('room-code-input').value.trim();
      const nameInput = document.getElementById('mood-name-input');
      const name = nameInput ? (nameInput.value || '').trim() : '';
      
      if (!roomId) {
        showToast('Please enter a room code', 'error');
        document.getElementById('room-code-input').focus();
        return;
      }
      
      if (name && name.length > 20) {
        showToast('Name must be 20 characters or less', 'error');
        if (nameInput) nameInput.focus();
        return;
      }
      
      if (name) {
        sessionStorage.setItem('moodUserName', name);
      }
      
      fetch(APP_CONFIG.API_BASE_URL + `/api/mood/join?roomId=${roomId}`, { method: 'POST' })
        .then(response => {
          if (response.ok) {
            window.location.href = `${window.location.origin}/mood?room=${roomId}`;
          } else {
            showToast('Room not found. Check the code and try again.', 'error');
          }
        })
        .catch(err => {
          showToast('Failed to join room. Please try again.', 'error', 4000);
        });
    });
  }

  // Home buttons
  const goHomeBtn = document.getElementById('go-home-btn');
  const goHomeTopBtn = document.getElementById('go-home-top-btn');
  if (goHomeBtn) {
    goHomeBtn.addEventListener('click', () => {
      window.location.href = window.location.origin + '/index.html';
    });
  }
  if (goHomeTopBtn) {
    goHomeTopBtn.addEventListener('click', () => {
      window.location.href = window.location.origin + '/index.html';
    });
  }
  var modeHomeBtn = document.getElementById('mood-mode-home-btn');
  if (modeHomeBtn) {
    modeHomeBtn.addEventListener('click', function () {
      window.location.href = window.location.origin + '/index.html';
    });
  }
  
  // Reveal results button (host only)
  // NEW: Supports multiple reveals - updates results with latest submitted data
  const revealBtn = document.getElementById('reveal-results-btn');
  if (revealBtn) {
    revealBtn.addEventListener('click', () => {
      if (currentRoomId && moodStompClient && moodStompClient.connected) {
        moodStompClient.send(`/app/mood.reveal.${currentRoomId}`, {}, JSON.stringify({
          userId: userId
        }));
        // Check if results already revealed to show appropriate message
        const resultsSection = document.getElementById('results-section');
        const isAlreadyRevealed = resultsSection && resultsSection.style.display !== 'none';
        if (isAlreadyRevealed) {
          showToast('Updating results with latest data...', 'info');
        } else {
          showToast('Revealing results...', 'info');
        }
      }
    });
  }

  // NEW: Export PDF button - REUSES: Same pattern as Sprint Retrospective export
  const exportPdfBtn = document.getElementById('export-pdf-btn');
  if (exportPdfBtn) {
    exportPdfBtn.addEventListener('click', () => {
      exportToPDF();
    });
  }
}

/**
 * Show mode selection
 */
function showModeSelection() {
  const modeSection = document.getElementById('mode-selection-section');
  if (modeSection) {
    modeSection.style.display = 'block';
  }
  
  const modeCards = document.querySelectorAll('.mode-card');
  modeCards.forEach(card => {
    card.addEventListener('click', () => {
      const mode = card.getAttribute('data-mode');
      selectMode(mode);
    });
    card.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        const mode = card.getAttribute('data-mode');
        selectMode(mode);
      }
    });
  });
}

/**
 * Select mode and create room with that mode
 * NEW: Mode selection creates the room (as per requirements)
 */
function selectMode(mode) {
  currentMode = mode;
  
  // Map frontend mode to backend mode
  const backendMode = mode === 'quick' ? 'QUICK_PULSE' : 'SCRUM_PULSE';
  
  // If we're already in a room (joined via link), set the mode on existing room
  if (currentRoomId && moodStompClient && moodStompClient.connected) {
    // Set mode on existing room via WebSocket
    moodStompClient.send(`/app/mood.setmode.${currentRoomId}`, {}, JSON.stringify({
      mode: backendMode
    }));
    
    // Hide mode selection and show survey
    hideModeSelection();
    showSurvey();
    
    // Start survey
    if (mode === 'quick') {
      showQuickPulseQuestion();
    } else {
      showScrumPulseQuestion(0);
    }
  } else {
    // Create new room with selected mode
    // Ensure a team name is assigned on room creation (localStorage-based)
    moodEnsureTeamNameAssigned();
    fetch(APP_CONFIG.API_BASE_URL + '/api/mood/create', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ mode: backendMode })
    })
      .then(response => {
        if (response.ok) {
          return response.text();
        }
        return response.text().then(text => Promise.reject(text));
      })
      .then(roomId => {
        if (roomId && /^[a-zA-Z0-9]{8}$/.test(roomId)) {
          // Redirect to room with mode selected
          window.location.href = `${window.location.origin}/mood?room=${roomId}`;
        } else {
          showToast('Failed to create room. Please try again.', 'error', 4000);
        }
      })
      .catch(err => {
        showToast(err || 'Failed to create room. Please try again.', 'error', 4000);
      });
  }
}

function hideModeSelection() {
  const modeSection = document.getElementById('mode-selection-section');
  if (modeSection) {
    modeSection.style.display = 'none';
  }
}

function showSurvey() {
  const surveySection = document.getElementById('survey-section');
  if (surveySection) {
    surveySection.style.display = 'block';
  }
}

/**
 * Show Quick Pulse question
 */
function showQuickPulseQuestion() {
  const container = document.getElementById('survey-container');
  if (!container) return;
  
  container.innerHTML = `
    <div class="question-container">
      <div class="question-title">${quickPulseQuestion.title}</div>
      <div class="mood-options">
        ${quickPulseQuestion.options.map(emoji => `
          <button class="mood-option" data-value="${emoji}" aria-label="${MOOD_ARIA_LABELS[emoji] || emoji}">${emoji}</button>
        `).join('')}
      </div>
    </div>
  `;
  
  container.classList.add('active');
  
  // Add event listeners
  const options = container.querySelectorAll('.mood-option');
  options.forEach(option => {
    option.addEventListener('click', () => {
      // Remove previous selection
      options.forEach(opt => opt.classList.remove('selected'));
      // Select this option
      option.classList.add('selected');
      
      // Auto-submit after short delay
      setTimeout(() => {
        submitQuickPulseResponse(option.getAttribute('data-value'));
      }, 500);
    });
  });
}

/**
 * Show Scrum Pulse question
 */
function showScrumPulseQuestion(questionIndex) {
  const container = document.getElementById('survey-container');
  if (!container) return;
  
  if (questionIndex >= scrumPulseQuestions.length) {
    // All questions answered, submit
    submitScrumPulseResponse();
    return;
  }
  
  const question = scrumPulseQuestions[questionIndex];
  currentQuestion = questionIndex;
  
  let questionHTML = `
    <div class="survey-progress">Question ${questionIndex + 1} of ${scrumPulseQuestions.length}</div>
    <div class="question-container">
      <div class="question-title">${question.title}</div>
  `;
  
  if (question.type === 'mood') {
    questionHTML += `
      <div class="mood-options">
        ${question.options.map(emoji => `
          <button class="mood-option" data-value="${emoji}" aria-label="${MOOD_ARIA_LABELS[emoji] || emoji}">${emoji}</button>
        `).join('')}
      </div>
    `;
  } else if (question.type === 'confidence') {
    questionHTML += `
      <div class="confidence-scale">
        ${question.options.map(num => `
          <button class="confidence-option" data-value="${num}" aria-label="${CONFIDENCE_ARIA_LABELS[num] || num}">${num}</button>
        `).join('')}
      </div>
    `;
  } else if (question.type === 'workload') {
    questionHTML += `
      <div class="workload-options">
        ${question.options.map(option => `
          <button class="workload-option" data-value="${option}">${option}</button>
        `).join('')}
      </div>
    `;
  } else if (question.type === 'blocked') {
    questionHTML += `
      <div class="blocked-options">
        ${question.options.map(option => `
          <button class="blocked-option" data-value="${option}">${option}</button>
        `).join('')}
      </div>
    `;
  } else if (question.type === 'comment') {
    questionHTML += `
      <textarea 
        id="comment-input" 
        class="comment-input" 
        placeholder="Share any additional thoughts (optional)..."
        maxlength="500"
      ></textarea>
      <button class="submit-btn" id="submit-comment-btn">
        <span class="btn-icon ic" data-ic="check" aria-hidden="true"></span>
        <span class="btn-text">Continue</span>
      </button>
    `;
  }
  
  questionHTML += `</div>`;
  
  container.innerHTML = questionHTML;
  if (typeof window.hydrateIcons === 'function') window.hydrateIcons(container);
  container.classList.add('active');
  
  // Add event listeners based on question type
  if (question.type === 'mood') {
    const options = container.querySelectorAll('.mood-option');
    options.forEach(option => {
      option.addEventListener('click', () => {
        options.forEach(opt => opt.classList.remove('selected'));
        option.classList.add('selected');
        responses[question.id] = option.getAttribute('data-value');
        setTimeout(() => {
          showScrumPulseQuestion(questionIndex + 1);
        }, 300);
      });
    });
  } else if (question.type === 'confidence') {
    const options = container.querySelectorAll('.confidence-option');
    options.forEach(option => {
      option.addEventListener('click', () => {
        options.forEach(opt => opt.classList.remove('selected'));
        option.classList.add('selected');
        responses[question.id] = parseInt(option.getAttribute('data-value'));
        setTimeout(() => {
          showScrumPulseQuestion(questionIndex + 1);
        }, 300);
      });
    });
  } else if (question.type === 'workload') {
    const options = container.querySelectorAll('.workload-option');
    options.forEach(option => {
      option.addEventListener('click', () => {
        options.forEach(opt => opt.classList.remove('selected'));
        option.classList.add('selected');
        responses[question.id] = option.getAttribute('data-value');
        setTimeout(() => {
          showScrumPulseQuestion(questionIndex + 1);
        }, 300);
      });
    });
  } else if (question.type === 'blocked') {
    const options = container.querySelectorAll('.blocked-option');
    options.forEach(option => {
      option.addEventListener('click', () => {
        options.forEach(opt => opt.classList.remove('selected'));
        option.classList.add('selected');
        responses[question.id] = option.getAttribute('data-value');
        setTimeout(() => {
          showScrumPulseQuestion(questionIndex + 1);
        }, 300);
      });
    });
  } else if (question.type === 'comment') {
    const submitBtn = document.getElementById('submit-comment-btn');
    if (submitBtn) {
      submitBtn.addEventListener('click', () => {
        const commentInput = document.getElementById('comment-input');
        responses[question.id] = commentInput ? (commentInput.value || '').trim() : '';
        showScrumPulseQuestion(questionIndex + 1);
      });
    }
    
    // Allow Enter key to submit (with Ctrl/Cmd)
    const commentInput = document.getElementById('comment-input');
    if (commentInput) {
      commentInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
          e.preventDefault();
          submitBtn.click();
        }
      });
    }
  }
}

/**
 * Submit Quick Pulse response
 */
function submitQuickPulseResponse(mood) {
  if (hasSubmitted) return;
  
  hasSubmitted = true;
  responses = { mood };
  
  // Disable all options
  const options = document.querySelectorAll('.mood-option');
  options.forEach(opt => {
    opt.style.pointerEvents = 'none';
  });
  
  // Send response via WebSocket (placeholder)
  sendMoodResponse(responses);
  
  showToast('Response submitted! ✅', 'success', 2000);
  
  // NEW: If results are already revealed, hide survey and show only results
  const resultsSection = document.getElementById('results-section');
  const surveySection = document.getElementById('survey-section');
  
  if (resultsSection && resultsSection.style.display !== 'none') {
    // Results are visible, hide survey section and show full results
    if (surveySection) {
      surveySection.style.display = 'none';
    }
    // Remove collapsed class and expand results
    resultsSection.classList.remove('collapsed');
    resultsSection.style.maxHeight = '2000px';
    resultsSection.style.opacity = '1';
    resultsSection.style.marginTop = '32px';
    resultsSection.style.marginBottom = '32px';
    // Remove notification banner
    const notification = document.querySelector('.results-available-notification');
    if (notification) {
      notification.remove();
    }
  } else {
    // Results not yet revealed, show waiting message
    const container = document.getElementById('survey-container');
    if (container) {
      container.innerHTML = `
        <div class="question-container">
          <div class="question-title">Thank you! Your response has been recorded.</div>
          <div style="color: var(--color-text-secondary); margin-top: 16px;">Waiting for all participants to respond...</div>
        </div>
      `;
    }
  }
}

/**
 * Submit Scrum Pulse response
 */
function submitScrumPulseResponse() {
  if (hasSubmitted) return;
  
  hasSubmitted = true;
  
  // Send response via WebSocket (placeholder)
  sendMoodResponse(responses);
  
  showToast('Response submitted! ✅', 'success', 2000);
  
  // NEW: If results are already revealed, hide survey and show only results
  const resultsSection = document.getElementById('results-section');
  const surveySection = document.getElementById('survey-section');
  
  if (resultsSection && resultsSection.style.display !== 'none') {
    // Results are visible, hide survey section and show full results
    if (surveySection) {
      surveySection.style.display = 'none';
    }
    // Remove collapsed class and expand results
    resultsSection.classList.remove('collapsed');
    resultsSection.style.maxHeight = '2000px';
    resultsSection.style.opacity = '1';
    resultsSection.style.marginTop = '32px';
    resultsSection.style.marginBottom = '32px';
    // Remove notification banner
    const notification = document.querySelector('.results-available-notification');
    if (notification) {
      notification.remove();
    }
  } else {
    // Results not yet revealed, show waiting message
    const container = document.getElementById('survey-container');
    if (container) {
      container.innerHTML = `
        <div class="question-container">
          <div class="question-title">Thank you! Your response has been recorded.</div>
          <div style="color: var(--color-text-secondary); margin-top: 16px;">Waiting for host to reveal results...</div>
        </div>
      `;
    }
  }
}

/**
 * Send mood response via WebSocket
 * REUSES: Same WebSocket pattern as retro.js
 */
function sendMoodResponse(responses) {
  if (!moodStompClient || !moodStompClient.connected) {
    showToast('Not connected to room. Please refresh.', 'error');
    return;
  }
  
  if (!currentRoomId) {
    showToast('Room ID missing. Please refresh.', 'error');
    return;
  }
  
  // Send response to backend
  moodStompClient.send(`/app/mood.response.${currentRoomId}`, {}, JSON.stringify({
    userId: userId,
    responses: responses
  }));
}

/**
 * Connect to WebSocket room
 * REUSES: Same WebSocket connection pattern as retro.js
 */
function connectToMoodRoom(roomId) {
  currentRoomId = roomId;
  
  // Check if already connected to another room
  const existingRoomId = sessionStorage.getItem('moodRoomId');
  if (existingRoomId && existingRoomId !== roomId) {
    if (!confirm('You are already in another mood room. Leave and join this one?')) {
      window.location.href = `${window.location.origin}/mood?room=${existingRoomId}`;
      return;
    }
    // Disconnect from previous room
    if (moodStompClient && moodStompClient.connected) {
      moodStompClient.send(`/app/mood.leave.${existingRoomId}`, {}, JSON.stringify({ userId: userId }));
      moodStompClient.disconnect();
    }
    sessionStorage.removeItem('moodRoomId');
  }
  
  // Connect to WebSocket
  const socket = new SockJS(APP_CONFIG.WS_BASE_URL);
  moodStompClient = Stomp.over(socket);
  
  moodStompClient.connect({}, function() {
    // Subscribe to room updates
    moodStompClient.subscribe(`/topic/mood.${roomId}`, function(message) {
      const data = JSON.parse(message.body);
      handleMoodMessage(data);
    });
    
    // Join the room
    moodStompClient.send(`/app/mood.join.${roomId}`, {}, JSON.stringify({
      userId: userId,
      userName: userName || 'Anonymous'
    }));
    
    sessionStorage.setItem('moodRoomId', roomId);
  }, function(error) {
    showToast('Connection lost - reconnecting...', 'warning');
    setTimeout(() => {
      connectToMoodRoom(roomId);
    }, 5000);
  });
  
  // Cleanup on page unload
  const cleanup = () => {
    if (moodStompClient && moodStompClient.connected && userId) {
      try {
        moodStompClient.send(`/app/mood.leave.${roomId}`, {}, JSON.stringify({ userId: userId }));
      } catch (e) {
        // Ignore errors during cleanup
      }
      moodStompClient.disconnect();
    }
    sessionStorage.removeItem('moodRoomId');
  };
  
  window.addEventListener('beforeunload', cleanup);
  window.addEventListener('pagehide', cleanup);
  window.addEventListener('unload', cleanup);
}

/**
 * Handle WebSocket messages from backend
 * REUSES: Similar message handling pattern as retro.js
 */
function handleMoodMessage(data) {
  // Handle errors
  if (data.error) {
    // Check if this error is for the current user
    const isRejected = data.rejectedUserId === userId;
    
    if (isRejected) {
      if (data.roomFull || (data.error && data.error.includes('full'))) {
        showToast(data.error || 'This room is full (max 10 participants).', 'error', 5000);
        setTimeout(() => {
          window.location.href = window.location.origin + '/index.html';
        }, 3000);
      } else {
        showToast(data.error, 'error');
      }
    } else {
      // Error for another user, but include state update if provided
      if (data.state) {
        updateRoomState(data.state);
      }
    }
    return;
  }
  
  // Handle state updates
  if (data.state) {
    updateRoomState(data.state);
  }
  
  // Handle progress updates
  if (data.type === 'PROGRESS_UPDATE') {
    updateProgress(data.submittedCount, data.totalUsers, data.allSubmitted);
    
    // Also update submission status in room-info section for host
    const roomInfo = document.getElementById('room-info');
    const hostNameEl = document.getElementById('host-name');
    const userNameEl = document.getElementById('user-name');
    const isHost = roomInfo && hostNameEl && userNameEl && hostNameEl.textContent === userNameEl.textContent;
    
    const submissionStatusEl = document.getElementById('submission-status');
    if (submissionStatusEl && isHost) {
      const resultsSection = document.getElementById('results-section');
      const isRevealed = resultsSection && resultsSection.style.display !== 'none';
      
      if (!isRevealed) {
        submissionStatusEl.style.display = 'block';
        const submissionCountEl = document.getElementById('submission-count');
        if (submissionCountEl) {
          submissionCountEl.textContent = `${data.submittedCount || 0} of ${data.totalUsers || 0} participants have submitted`;
        }
      } else {
        submissionStatusEl.style.display = 'none';
      }
    }
  }
  
  // Handle mode set
  if (data.type === 'MODE_SET' && data.mode) {
    currentMode = data.mode === 'QUICK_PULSE' ? 'quick' : 'scrum';
    hideModeSelection();
    showSurvey();
    
    // Start survey if user hasn't submitted
    if (!hasSubmitted) {
      if (currentMode === 'quick') {
        showQuickPulseQuestion();
      } else {
        showScrumPulseQuestion(0);
      }
    }
  }
  
  // Handle results ready
  if (data.type === 'RESULTS_READY' && data.results) {
    displayResultsFromBackend(data.results);
    // Update state if provided (so button text updates to "Update Results")
    if (data.state) {
      updateRoomState(data.state);
    }
  }
  
  // Handle join success
  if (data.allowed === true && data.state) {
    updateRoomState(data.state);
    
    // Check if we need to show mode selection or survey
    const state = data.state;
    if (state.mode) {
      // Room already has a mode, set it and show survey
      currentMode = state.mode === 'QUICK_PULSE' ? 'quick' : 'scrum';
      hideModeSelection();
      showSurvey();
      
      // If results are already revealed, show them
      if (state.resultsRevealed && state.results) {
        displayResultsFromBackend(state.results);
      } else if (!hasSubmitted) {
        // Show survey if user hasn't submitted yet
        if (currentMode === 'quick') {
          showQuickPulseQuestion();
        } else {
          showScrumPulseQuestion(0);
        }
      }
    } else {
      // Room doesn't have a mode yet (shouldn't happen in normal flow, but handle it)
      // This means room was created without mode - show mode selection
      showModeSelection();
    }
  }
}

/**
 * Update room state from backend
 * LAYOUT UPDATE: Now matches Poker Planning and Retrospective structure
 * - Room info at top with tool name, mode, session status
 * - Participants toolbar below room info
 * - Content sections (mode selection, survey, results) below toolbar
 */
function updateRoomState(state) {
  // NEW: Store data for PDF export
  if (state.hostName) {
    pdfExportData.hostName = state.hostName;
  }
  if (state.names && typeof state.names === 'object') {
    pdfExportData.participantNames = Object.values(state.names);
  }
  if (state.mode) {
    pdfExportData.mode = state.mode;
  }
  if (currentRoomId) {
    pdfExportData.roomId = currentRoomId;
  }
  
  // LAYOUT UPDATE: Update room-info section to match Poker/Retro format exactly
  // Show user name (matches Poker/Retro)
  // FIX: Ensure userName is always displayed when available
  const userNameEl = document.getElementById('user-name');
  if (userNameEl) {
    // Get userName from sessionStorage if not already set
    if (!userName) {
      userName = sessionStorage.getItem('moodUserName') || '';
    }
    if (userName) {
      userNameEl.textContent = userName;
    } else {
      // If still no userName, try to get it from state.names using userId
      if (state.names && typeof state.names === 'object' && userId) {
        const nameFromState = state.names[userId];
        if (nameFromState) {
          userName = nameFromState;
          userNameEl.textContent = userName;
        }
      }
    }
  }
  
  // Update host name display (matches Poker/Retro format)
  if (state.hostName) {
    const hostInfo = document.getElementById('host-info');
    const hostNameEl = document.getElementById('host-name');
    if (hostInfo && hostNameEl) {
      hostNameEl.textContent = state.hostName;
      hostInfo.style.display = 'block';
    }
  }
  
  // LAYOUT UPDATE: Update participants toolbar (matches Poker/Retro structure)
  const participantsToolbar = document.getElementById('participants-toolbar');
  const moodUserCount = document.getElementById('mood-user-count');
  const moodCurrentUsers = document.getElementById('mood-current-users');
  const moodTeamMembersList = document.getElementById('mood-team-members-list');
  
  if (state.names && typeof state.names === 'object') {
    const names = Object.values(state.names);
    const totalUsers = names.length;
    
    // Show participants toolbar when room is active
    if (participantsToolbar) {
      participantsToolbar.style.display = 'flex';
    }
    
    // Update participant count
    if (moodUserCount) {
      moodUserCount.textContent = totalUsers;
    }
    if (moodCurrentUsers) {
      moodCurrentUsers.textContent = totalUsers;
    }
    
    // Update team members list (matches Poker/Retro styling)
    // NEW: Show ALL participants' names to host (not limited to first 5)
    if (moodTeamMembersList && names.length > 0) {
      moodTeamMembersList.innerHTML = '';
      // Create team members names container (matches CSS structure)
      const teamMembersNames = document.createElement('div');
      teamMembersNames.className = 'team-members-names';
      
      // Show all names (no limit)
      names.forEach((name, index) => {
        const isHost = state.hostName && name === state.hostName;
        const memberBadge = document.createElement('span');
        memberBadge.className = 'team-member-badge';
        if (isHost) {
          memberBadge.innerHTML = '<span class="ic" data-ic="crown" aria-hidden="true"></span> ';
          memberBadge.appendChild(document.createTextNode(name));
          if (typeof window.hydrateIcons === 'function') window.hydrateIcons(memberBadge);
        } else {
          memberBadge.textContent = name;
        }
        teamMembersNames.appendChild(memberBadge);
      });
      
      moodTeamMembersList.appendChild(teamMembersNames);
      moodTeamMembersList.style.display = 'flex';
    } else if (moodTeamMembersList) {
      moodTeamMembersList.style.display = 'none';
    }
  } else if (participantsToolbar) {
    participantsToolbar.style.display = 'none';
  }
  
  // Show/hide reveal results button (only for host)
  // NEW: Allow multiple reveals - button always visible to host, even after results revealed
  const revealBtn = document.getElementById('reveal-results-btn');
  if (revealBtn && state.hostName && state.names) {
    const isHost = state.names[userId] === state.hostName;
    if (isHost) {
      // Always show reveal button to host (allows multiple reveals with updated data)
      revealBtn.style.display = 'inline-block';
      // Update button text if results already revealed
      if (state.resultsRevealed) {
        revealBtn.innerHTML = '<span class="ic" data-ic="refresh" aria-hidden="true"></span> Update Results';
      } else {
        revealBtn.innerHTML = '<span class="ic" data-ic="target" aria-hidden="true"></span> Reveal Results';
      }
      if (typeof window.hydrateIcons === 'function') window.hydrateIcons(revealBtn);
    } else {
      revealBtn.style.display = 'none';
    }
  }
  
  // Show submission count to host in room-info section (only before results revealed)
  // REDUCED VISUAL NOISE: Hide submission status after results revealed
  if (state.hostName && state.names) {
    const isHost = state.names[userId] === state.hostName;
    const submissionStatusEl = document.getElementById('submission-status');
    if (submissionStatusEl && isHost && !state.resultsRevealed) {
      submissionStatusEl.style.display = 'block';
      const submissionCountEl = document.getElementById('submission-count');
      if (submissionCountEl) {
        submissionCountEl.textContent = `${state.submittedCount || 0} of ${state.totalUsers || 0} participants have submitted`;
      }
    } else if (submissionStatusEl) {
      // Hide after results revealed to reduce visual noise
      submissionStatusEl.style.display = 'none';
    }
  }
  
  // NEW: Show/hide export PDF button (visible only after results revealed)
  const exportPdfBtn = document.getElementById('export-pdf-btn');
  if (exportPdfBtn) {
    if (state.resultsRevealed) {
      exportPdfBtn.style.display = 'inline-block';
    } else {
      exportPdfBtn.style.display = 'none';
    }
  }
  
  // Update user count, submission status, etc.
  if (state.submittedCount !== undefined && state.totalUsers !== undefined) {
    updateProgress(state.submittedCount, state.totalUsers, state.allSubmitted);
  }
  
  // Check if results are revealed
  if (state.resultsRevealed && state.results) {
    displayResultsFromBackend(state.results);
  }
}

/**
 * Update progress indicator
 * Shows different message for participants vs host
 */
function updateProgress(submittedCount, totalUsers, allSubmitted) {
  const container = document.getElementById('survey-container');
  if (container && hasSubmitted) {
    // Check if current user is host (need to get state)
    const roomInfo = document.getElementById('room-info');
    const hostNameEl = document.getElementById('host-name');
    const userNameEl = document.getElementById('user-name');
    const isHost = hostNameEl && userNameEl && hostNameEl.textContent === userNameEl.textContent;
    
    if (isHost) {
      // Host sees submission count and can reveal results
      container.innerHTML = `
        <div class="question-container">
          <div class="question-title">Thank you! Your response has been recorded.</div>
          <div style="color: var(--color-text-secondary); margin-top: 16px;">
            <strong>${submittedCount} of ${totalUsers} participants have submitted</strong><br/>
            <small>Click "Reveal Results" when ready to show results to all participants</small>
          </div>
        </div>
      `;
    } else {
      // Participants see waiting message
      container.innerHTML = `
        <div class="question-container">
          <div class="question-title">Thank you! Your response has been recorded.</div>
          <div style="color: var(--color-text-secondary); margin-top: 16px;">
            Waiting for host to reveal results...<br/>
            <small>${submittedCount} of ${totalUsers} responses received</small>
          </div>
        </div>
      `;
    }
  }
}

/**
 * Display aggregated results from backend
 * NEW: Uses server-side aggregated results instead of frontend calculation
 * NEW: Option 1 - Users who haven't submitted can still submit after results are revealed
 * - If user has NOT submitted: Keep survey visible, show results below in collapsible section
 * - If user has submitted: Hide survey, show only results (current behavior)
 */
function displayResultsFromBackend(results) {
  // NEW: Store results data for PDF export
  pdfExportData.results = results;
  pdfExportData.date = new Date();
  pdfExportData.mode = results.mode || 'QUICK_PULSE';
  
  const resultsSection = document.getElementById('results-section');
  const surveySection = document.getElementById('survey-section');
  
  // NEW: Check if user has submitted before hiding survey
  if (hasSubmitted) {
    // User has submitted: Hide survey, show only results (current behavior)
    if (surveySection) {
      surveySection.style.display = 'none';
    }
    
    if (resultsSection) {
      resultsSection.style.display = 'block';
      // Remove collapsed class if present
      resultsSection.classList.remove('collapsed');
      resultsSection.style.maxHeight = '';
      resultsSection.style.opacity = '';
      resultsSection.style.marginTop = '';
      resultsSection.style.marginBottom = '';
    }
    
    // Remove notification banner if present
    const notification = document.querySelector('.results-available-notification');
    if (notification) {
      notification.remove();
    }
  } else {
    // User has NOT submitted: Keep survey visible, show results below in collapsible section
    if (surveySection) {
      surveySection.style.display = 'block';
    }
    
    if (resultsSection) {
      resultsSection.style.display = 'block';
    }
    
    // Show notification banner in survey section
    showResultsAvailableNotification();
  }
  
  const container = document.getElementById('results-container');
  if (!container) return;
  
  const totalResponses = results.totalResponses || 0;
  const mode = results.mode || 'QUICK_PULSE';
  const isScrumPulse = mode === 'SCRUM_PULSE';
  
  // NEW: Show export PDF button when results are displayed
  const exportPdfBtn = document.getElementById('export-pdf-btn');
  if (exportPdfBtn) {
    exportPdfBtn.style.display = 'inline-block';
  }
  
  // Build results HTML
  let resultsHTML = `
    <div class="results-header">
      <div class="results-title">Team Mood Results</div>
      <div class="results-subtitle">${totalResponses} ${totalResponses === 1 ? 'response' : 'responses'}</div>
    </div>
  `;
  
  // Mood distribution (both modes)
  if (results.moodDistribution) {
    const moodEmojis = ['😞', '😐', '🙂', '😄', '🚀'];
    resultsHTML += `
      <div class="result-card">
        <div class="result-card-title">Mood Distribution</div>
        <div class="mood-distribution">
          ${moodEmojis.map(emoji => `
            <div class="mood-dist-item">
              <div class="mood-dist-emoji">${emoji}</div>
              <div class="mood-dist-count">${results.moodDistribution[emoji] || 0}</div>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }
  
  // Average confidence (only for Scrum Pulse)
  if (isScrumPulse && results.averageConfidence !== null && results.averageConfidence !== undefined) {
    resultsHTML += `
      <div class="result-card">
        <div class="result-card-title">Average Sprint Confidence</div>
        <div class="average-confidence">
          <div class="confidence-score">${results.averageConfidence.toFixed(1)}</div>
          <div style="color: var(--color-text-secondary); font-size: 15px; letter-spacing: 0.2px; line-height: 1.5;">Out of 5</div>
        </div>
      </div>
    `;
  }
  
  // Workload breakdown (only for Scrum Pulse)
  if (isScrumPulse && results.workloadBreakdown) {
    const workloadCounts = results.workloadBreakdown;
    const maxWorkload = Math.max(...Object.values(workloadCounts));
    resultsHTML += `
      <div class="result-card">
        <div class="result-card-title">Workload Balance</div>
        <div class="workload-breakdown">
          ${Object.entries(workloadCounts).map(([label, count]) => `
            <div class="workload-item">
              <div class="workload-label">${label}</div>
              <div class="workload-bar">
                <div class="workload-bar-fill" style="width: ${maxWorkload > 0 ? (count / maxWorkload) * 100 : 0}%"></div>
              </div>
              <div class="workload-count">${count}</div>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }
  
  // Blocker count (only for Scrum Pulse)
  if (isScrumPulse && results.blockerCount !== undefined) {
    const blockedCount = results.blockerCount;
    resultsHTML += `
      <div class="result-card">
        <div class="result-card-title">Team Blockers</div>
        <div class="blocker-count">
          <div class="blocker-number">${blockedCount}</div>
          <div style="color: var(--color-text-secondary); font-size: 15px; letter-spacing: 0.2px; line-height: 1.5;">${blockedCount === 1 ? 'person is' : 'people are'} currently blocked</div>
        </div>
      </div>
    `;
  }
  
  // Comments (only for Scrum Pulse, if any)
  if (isScrumPulse && results.comments && results.comments.length > 0) {
    resultsHTML += `
      <div class="result-card">
        <div class="result-card-title">Anonymous Comments</div>
        <div class="comments-list">
          ${results.comments.map(comment => `
            <div class="comment-item">
              <div class="comment-text">${escapeHtml(comment)}</div>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }
  
  container.innerHTML = resultsHTML;
  container.classList.add('active');
  
  // NEW: If user hasn't submitted, make results section collapsible
  if (!hasSubmitted) {
    const resultsSection = document.getElementById('results-section');
    if (resultsSection) {
      // Use setTimeout to ensure DOM is updated
      setTimeout(() => {
        makeResultsCollapsible();
      }, 100);
    }
  }
}

/**
 * NEW: Show notification banner that results are available (for users who haven't submitted)
 */
function showResultsAvailableNotification() {
  const surveyContainer = document.getElementById('survey-container');
  if (!surveyContainer) return;
  
  // Check if notification already exists
  let notification = surveyContainer.querySelector('.results-available-notification');
  
  if (!notification) {
    notification = document.createElement('div');
    notification.className = 'results-available-notification';
    notification.innerHTML = `
      <div style="background: var(--color-warning-bg); border: 1px solid var(--color-warning); border-radius: var(--radius-badge); padding: 16px; margin-bottom: 20px;">
        <div style="display: flex; align-items: center; gap: 12px;">
          <div class="ic" data-ic="bar-chart" aria-hidden="true" style="font-size: 24px;"></div>
          <div style="flex: 1;">
            <div style="font-weight: 600; color: var(--color-warning-dark); margin-bottom: 4px; font-size: 0.95rem;">Results are available!</div>
            <div style="color: var(--color-text-secondary); font-size: 0.85rem; line-height: 1.5;">
              The host has revealed results. You can still submit your response below, and results will update automatically.
            </div>
          </div>
          <button id="toggle-results-btn" class="v1-btn v1-btn-warning" style="white-space: nowrap;">
            View Results ⬇️
          </button>
        </div>
      </div>
    `;
    
    // Insert at the top of survey container
    const firstChild = surveyContainer.firstElementChild;
    if (firstChild) {
      surveyContainer.insertBefore(notification, firstChild);
    } else {
      surveyContainer.appendChild(notification);
    }
    if (typeof window.hydrateIcons === 'function') window.hydrateIcons(notification);
    
    // Add click handler to toggle results visibility
    const toggleBtn = notification.querySelector('#toggle-results-btn');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        const resultsSection = document.getElementById('results-section');
        if (resultsSection) {
          const isCollapsed = resultsSection.classList.contains('collapsed');
          if (isCollapsed) {
            resultsSection.classList.remove('collapsed');
            toggleBtn.textContent = 'Hide Results ⬆️';
          } else {
            resultsSection.classList.add('collapsed');
            toggleBtn.textContent = 'View Results ⬇️';
          }
        }
      });
    }
  }
}

/**
 * NEW: Make results section collapsible for users who haven't submitted
 */
function makeResultsCollapsible() {
  const resultsSection = document.getElementById('results-section');
  if (!resultsSection) return;
  
  // Initially collapsed
  resultsSection.classList.add('collapsed');
  
  // Add smooth transition
  resultsSection.style.transition = 'max-height 0.3s ease-out, opacity 0.3s ease-out, margin 0.3s ease-out';
  resultsSection.style.overflow = 'hidden';
  
  // Update height based on collapsed state
  const updateHeight = () => {
    if (resultsSection.classList.contains('collapsed')) {
      resultsSection.style.maxHeight = '0';
      resultsSection.style.opacity = '0';
      resultsSection.style.marginTop = '0';
      resultsSection.style.marginBottom = '0';
      resultsSection.style.paddingTop = '0';
      resultsSection.style.paddingBottom = '0';
    } else {
      resultsSection.style.maxHeight = '2000px'; // Large enough for content
      resultsSection.style.opacity = '1';
      resultsSection.style.marginTop = '32px';
      resultsSection.style.marginBottom = '32px';
    }
  };
  
  updateHeight();
  
  // Watch for class changes
  const observer = new MutationObserver(updateHeight);
  observer.observe(resultsSection, {
    attributes: true,
    attributeFilter: ['class']
  });
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

/**
 * NEW: Export Team Mood Check results as PDF
 * REUSES: Same PDF generation approach as Sprint Retrospective export
 * Uses jsPDF library (same as retro.js) to generate PDF client-side
 */
async function exportToPDF() {
  // Check if we have results data
  if (!pdfExportData.results) {
    showToast('No results to export. Please wait for results to be revealed.', 'error');
    return;
  }

  const exportBtn = document.getElementById('export-pdf-btn');
  const originalBtnContent = exportBtn ? exportBtn.innerHTML : '';
  
  if (exportBtn) {
    exportBtn.disabled = true;
    exportBtn.innerHTML = '<span class="btn-icon">⏳</span><span class="btn-text">Generating...</span>';
  }

  try {
    showToast('Generating PDF... 📄', 'info', 2000);

    // Wait for jsPDF to be available (REUSES: Same pattern as retro.js)
    let JsPDF = null;
    let attempts = 0;
    const maxAttempts = 10;
    
    while (attempts < maxAttempts && !JsPDF) {
      await new Promise(resolve => setTimeout(resolve, 200));
      if (window.jsPDF) {
        JsPDF = window.jsPDF;
      } else if (window.jspdf && window.jspdf.jsPDF) {
        JsPDF = window.jspdf.jsPDF;
        window.jsPDF = JsPDF;
      }
      attempts++;
    }

    if (!JsPDF) {
      // Try to load jsPDF dynamically (REUSES: Same fallback as retro.js)
      await new Promise((resolve, reject) => {
        const script = document.getElementById('jspdf-script');
        if (script) {
          if (script.dataset.loaded === 'true') {
            if (window.jspdf && window.jspdf.jsPDF) {
              JsPDF = window.jspdf.jsPDF;
              window.jsPDF = JsPDF;
              resolve();
              return;
            }
          }
          const checkLoad = () => {
            if (window.jspdf && window.jspdf.jsPDF) {
              JsPDF = window.jspdf.jsPDF;
              window.jsPDF = JsPDF;
              script.dataset.loaded = 'true';
              resolve();
            } else {
              reject(new Error('jsPDF script loaded but not accessible'));
            }
          };
          script.addEventListener('load', () => setTimeout(checkLoad, 100));
          script.addEventListener('error', () => reject(new Error('Failed to load jsPDF')));
          setTimeout(() => reject(new Error('jsPDF load timeout')), 5000);
        } else {
          reject(new Error('jsPDF script not found'));
        }
      });
    }

    if (!JsPDF) {
      throw new Error('jsPDF library not available. Please check your internet connection and refresh the page.');
    }

    // Create PDF document (REUSES: Same settings as retro.js)
    const doc = new JsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    const pageWidth = doc.internal.pageSize.getWidth();
    const pageHeight = doc.internal.pageSize.getHeight();
    const margin = 15;
    const contentWidth = pageWidth - 2 * margin;
    let yPos = margin + 30;

    // Helper function for gradient background (REUSES: Same pattern as retro.js)
    function addGradientBackground(colorIndex) {
      const gradients = [
        [[102, 126, 234], [118, 75, 162]],  // Purple-blue
        [[16, 185, 129], [5, 150, 105]],    // Green
        [[245, 158, 11], [217, 119, 6]],    // Orange
        [[59, 130, 246], [29, 78, 216]]     // Blue
      ];
      const [startColor, endColor] = gradients[colorIndex - 1] || gradients[0];
      
      for (let y = 0; y < pageHeight; y += 5) {
        const ratio = y / pageHeight;
        const r = Math.round(startColor[0] + (endColor[0] - startColor[0]) * ratio);
        const g = Math.round(startColor[1] + (endColor[1] - startColor[1]) * ratio);
        const b = Math.round(startColor[2] + (endColor[2] - startColor[2]) * ratio);
        doc.setFillColor(r, g, b);
        doc.rect(0, y, pageWidth, 5, 'F');
      }
    }

    // Helper function for border (REUSES: Same pattern as retro.js)
    function addBorder() {
      doc.setDrawColor(255, 255, 255);
      doc.setLineWidth(3);
      doc.rect(margin - 5, margin - 5, contentWidth + 10, pageHeight - 2 * margin + 10);
      doc.setLineWidth(1);
      doc.setDrawColor(0, 0, 0, 20);
      doc.rect(margin - 3, margin - 3, contentWidth + 6, pageHeight - 2 * margin + 6);
    }

    // Cover page - original format
    addGradientBackground(1);
    addBorder();

    // Title
    doc.setTextColor(255, 255, 255);
    doc.setFontSize(36);
    doc.setFont('helvetica', 'bold');
    doc.text('Team Mood Check', pageWidth / 2, yPos, { align: 'center' });
    
    yPos += 15;
    doc.setFontSize(24);
    doc.setFont('helvetica', 'normal');
    const modeName = pdfExportData.mode === 'SCRUM_PULSE' ? 'Scrum Pulse' : 'Quick Pulse';
    doc.text(`${modeName} Report`, pageWidth / 2, yPos, { align: 'center' });

    // Date and time
    yPos += 25;
    const now = pdfExportData.date || new Date();
    const dateStr = now.toLocaleDateString('en-US', { 
      weekday: 'long', 
      year: 'numeric', 
      month: 'long', 
      day: 'numeric' 
    });
    const timeStr = now.toLocaleTimeString('en-US', { 
      hour: '2-digit', 
      minute: '2-digit' 
    });

    doc.setFontSize(18);
    doc.setFont('helvetica', 'normal');
    doc.text('Date:', pageWidth / 2, yPos, { align: 'center' });
    yPos += 10;
    doc.setFontSize(16);
    doc.text(dateStr, pageWidth / 2, yPos, { align: 'center' });
    yPos += 10;
    doc.text(timeStr, pageWidth / 2, yPos, { align: 'center' });

    // Team information
    yPos += 25;
    doc.setFontSize(24);
    doc.setFont('helvetica', 'bold');
    doc.text('Team Information', pageWidth / 2, yPos, { align: 'center' });
    yPos += 15;

    doc.setFontSize(16);
    doc.setFont('helvetica', 'normal');
    
    // Host name
    if (pdfExportData.hostName) {
      doc.text(`Host: ${pdfExportData.hostName}`, pageWidth / 2, yPos, { align: 'center' });
      yPos += 10;
    }

    // Participants
    const participants = pdfExportData.participantNames || [];
    const totalParticipants = participants.length;
    doc.text(`Participants (${totalParticipants}):`, pageWidth / 2, yPos, { align: 'center' });
    yPos += 10;

    if (participants.length > 0) {
      participants.forEach((name, index) => {
        if (yPos > pageHeight - margin - 20) {
          doc.addPage();
          addGradientBackground(1);
          addBorder();
          yPos = margin + 30;
        }
        doc.text(`• ${name}`, pageWidth / 2, yPos, { align: 'center' });
        yPos += 8;
      });
    } else {
      doc.text('No participants', pageWidth / 2, yPos, { align: 'center' });
      yPos += 8;
    }

    // Results page
    doc.addPage();
    addGradientBackground(2);
    addBorder();
    yPos = margin + 30;

    doc.setTextColor(255, 255, 255);
    doc.setFontSize(32);
    doc.setFont('helvetica', 'bold');
    doc.text('Results Summary', pageWidth / 2, yPos, { align: 'center' });
    yPos += 20;

    const results = pdfExportData.results;
    const isScrumPulse = pdfExportData.mode === 'SCRUM_PULSE';

    // Mood Distribution (both modes) - emoji replacement with text labels
    if (results.moodDistribution) {
      doc.setFontSize(24);
      doc.setFont('helvetica', 'bold');
      doc.text('Mood Distribution', pageWidth / 2, yPos, { align: 'center' });
      yPos += 15;

      doc.setFontSize(16);
      doc.setFont('helvetica', 'normal');
      
      // NEW: Map emojis to text labels for PDF (emojis don't render well in jsPDF)
      const moodLabels = [
        { emoji: '😞', label: 'Very Low' },
        { emoji: '😐', label: 'Low' },
        { emoji: '🙂', label: 'Neutral' },
        { emoji: '😄', label: 'High' },
        { emoji: '🚀', label: 'Very High' }
      ];
      
      moodLabels.forEach(({ emoji, label }) => {
        const count = results.moodDistribution[emoji] || 0;
        doc.text(`${label} ${count}`, pageWidth / 2, yPos, { align: 'center' });
        yPos += 10;
      });
      yPos += 5;
    }

    // Quick Pulse: Calculate average mood score
    if (!isScrumPulse && results.moodDistribution) {
      const moodScores = { '😞': 1, '😐': 2, '🙂': 3, '😄': 4, '🚀': 5 };
      let totalScore = 0;
      let totalCount = 0;
      Object.entries(results.moodDistribution).forEach(([emoji, count]) => {
        if (moodScores[emoji]) {
          totalScore += moodScores[emoji] * count;
          totalCount += count;
        }
      });
      const avgScore = totalCount > 0 ? (totalScore / totalCount).toFixed(1) : 0;
      
      if (yPos > pageHeight - margin - 30) {
        doc.addPage();
        addGradientBackground(2);
        addBorder();
        yPos = margin + 30;
      }

      doc.setFontSize(20);
      doc.setFont('helvetica', 'bold');
      doc.text('Average Mood Score', pageWidth / 2, yPos, { align: 'center' });
      yPos += 15;
      doc.setFontSize(36);
      doc.setFont('helvetica', 'normal');
      doc.text(`${avgScore} / 5.0`, pageWidth / 2, yPos, { align: 'center' });
      yPos += 20;
    }

    // Scrum Pulse: Additional metrics
    if (isScrumPulse) {
      // Average Confidence
      if (results.averageConfidence !== null && results.averageConfidence !== undefined) {
        if (yPos > pageHeight - margin - 30) {
          doc.addPage();
          addGradientBackground(3);
          addBorder();
          yPos = margin + 30;
        }

        doc.setFontSize(20);
        doc.setFont('helvetica', 'bold');
        doc.text('Average Sprint Confidence', pageWidth / 2, yPos, { align: 'center' });
        yPos += 15;
        doc.setFontSize(36);
        doc.setFont('helvetica', 'normal');
        doc.text(`${results.averageConfidence.toFixed(1)} / 5.0`, pageWidth / 2, yPos, { align: 'center' });
        yPos += 20;
      }

      // Workload Breakdown
      if (results.workloadBreakdown) {
        if (yPos > pageHeight - margin - 40) {
          doc.addPage();
          addGradientBackground(3);
          addBorder();
          yPos = margin + 30;
        }

        doc.setFontSize(20);
        doc.setFont('helvetica', 'bold');
        doc.text('Workload Balance', pageWidth / 2, yPos, { align: 'center' });
        yPos += 15;

        doc.setFontSize(16);
        doc.setFont('helvetica', 'normal');
        Object.entries(results.workloadBreakdown).forEach(([label, count]) => {
          doc.text(`${label}: ${count}`, pageWidth / 2, yPos, { align: 'center' });
          yPos += 10;
        });
        yPos += 5;
      }

      // Blocker Count
      if (results.blockerCount !== undefined) {
        if (yPos > pageHeight - margin - 30) {
          doc.addPage();
          addGradientBackground(3);
          addBorder();
          yPos = margin + 30;
        }

        doc.setFontSize(20);
        doc.setFont('helvetica', 'bold');
        doc.text('Team Blockers', pageWidth / 2, yPos, { align: 'center' });
        yPos += 15;
        doc.setFontSize(36);
        doc.setFont('helvetica', 'normal');
        const blockerText = results.blockerCount === 1 ? 'person is' : 'people are';
        doc.text(`${results.blockerCount} ${blockerText} blocked`, pageWidth / 2, yPos, { align: 'center' });
        yPos += 20;
      }

      // Comments (only if threshold met)
      if (results.comments && results.comments.length > 0) {
        if (yPos > pageHeight - margin - 50) {
          doc.addPage();
          addGradientBackground(4);
          addBorder();
          yPos = margin + 30;
        }

        doc.setFontSize(20);
        doc.setFont('helvetica', 'bold');
        doc.text('Anonymous Comments', pageWidth / 2, yPos, { align: 'center' });
        yPos += 15;

        doc.setFontSize(14);
        doc.setFont('helvetica', 'normal');
        results.comments.forEach(comment => {
          if (yPos > pageHeight - margin - 20) {
            doc.addPage();
            addGradientBackground(4);
            addBorder();
            yPos = margin + 30;
          }
          const lines = doc.splitTextToSize(comment, contentWidth - 20);
          doc.text(lines, pageWidth / 2, yPos, { align: 'center' });
          yPos += lines.length * 7 + 5;
        });
      }
    }

    // Footer on last page
    doc.setTextColor(100, 100, 100);
    doc.setFontSize(10);
    doc.setFont('helvetica', 'normal');
    doc.text('Generated by Scrum Ceremonies', pageWidth / 2, pageHeight - margin, { align: 'center' });

    // Save PDF
    const roomId = pdfExportData.roomId || 'mood';
    const dateStrForFilename = now.toISOString().split('T')[0];
    const modeStr = pdfExportData.mode === 'SCRUM_PULSE' ? 'scrum-pulse' : 'quick-pulse';
    const filename = `mood-check-${modeStr}-${roomId}-${dateStrForFilename}.pdf`;
    doc.save(filename);

    showToast('PDF exported successfully! 🎉', 'success', 3000);
  } catch (error) {
    console.error('PDF export error:', error);
    showToast('Failed to generate PDF. Please try again.', 'error', 4000);
  } finally {
    if (exportBtn) {
      exportBtn.disabled = false;
      exportBtn.innerHTML = originalBtnContent;
    }
  }
}

/**
 * Initialize on page load
 * REUSES: Same pattern as Poker Planning - redirects to join page if no name
 */
window.onload = function() {
  initializeEventListeners();
  
  const roomId = getRoomIdFromUrl();
  if (roomId) {
    userId = generateUserId();
    userName = sessionStorage.getItem('moodUserName') || '';
    
    // If user has room ID but no name, this shouldn't happen since name is mandatory
    // But handle gracefully by showing error
    if (!userName || userName.trim() === '') {
      showToast('Name is required. Please enter your name.', 'error');
      // Redirect back to home to enter name
      setTimeout(() => {
        window.location.href = '/mood';
      }, 2000);
      return;
    }
    
    // Hide room creation section
    const roomSection = document.getElementById('room-section');
    if (roomSection) {
      roomSection.style.display = 'none';
    }
    
    // Hide "How to Use" section when room is joined
    const rulesSection = document.getElementById('mood-rules-section');
    if (rulesSection) {
      rulesSection.style.display = 'none';
    }
    
    // LAYOUT UPDATE: Show room info at top (matches Poker/Retro layout)
    const roomInfo = document.getElementById('room-info');
    const roomCode = document.getElementById('room-code');
    const userNameEl = document.getElementById('user-name');
    if (roomInfo && roomCode) {
      roomCode.textContent = roomId;
      roomInfo.style.display = 'block';
    }
    // FIX: Ensure userName is always displayed
    if (userNameEl) {
      // Get userName from sessionStorage if not already set
      if (!userName) {
        userName = sessionStorage.getItem('moodUserName') || '';
      }
      if (userName) {
        userNameEl.textContent = userName;
      }
    }
    moodUpdateTeamNameUI();
    
    // Connect to room first
    connectToMoodRoom(roomId);
    
    // Mode selection and survey will be shown after WebSocket connection
    // based on room state from backend
    showToast('Connecting to room...', 'info');
  } else {
    // Allow Enter key to submit forms
    const nameInput = document.getElementById('mood-name-input');
    const roomCodeInput = document.getElementById('room-code-input');
    
    if (nameInput) {
      nameInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          document.getElementById('create-room-btn').click();
        }
      });
    }
    
    if (roomCodeInput) {
      roomCodeInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          document.getElementById('join-room-btn').click();
        }
      });
    }
  }
};


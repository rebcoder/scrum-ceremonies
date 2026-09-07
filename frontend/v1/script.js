/**
 * Copyright (c) 2026 rebcoder. MIT licensed — see LICENSE.
 * 
 * Planning Poker - JavaScript Logic
 * Handles room creation, joining, voting, and real-time updates
 */

let stompClient = null;
let userId = null;
let userName = null;
let currentRoomId = null;
let joinedUsers = new Set();
let joinedUsernames = new Set();
let statePollTimer = null;

// Team name utilities loaded from shared.js

function updateTeamNameUI() {
  const teamNameEl = document.getElementById('team-name');
  if (!teamNameEl) return;
  teamNameEl.textContent = getStoredTeamName() || 'Team';
}

// Simple toast notification system (used by copy/share UX and tests)
function showToast(message, type = 'info', duration = 2500) {
  try {
    const container =
      document.getElementById('toast-container') ||
      document.querySelector('.toast-container') ||
      document.body;

    const toast = document.createElement('div');
    toast.className = `toast-message toast-${type}`;
    toast.setAttribute('role', 'status');
    toast.setAttribute('aria-live', 'polite');
    toast.textContent = message;

    container.appendChild(toast);

    setTimeout(() => {
      if (toast && toast.parentNode) toast.parentNode.removeChild(toast);
    }, duration);
  } catch (e) {
    // no-op: toast is non-critical
    console.log('Toast error', e);
  }
}
window.showToast = showToast;

function generateUserId() {
  return Math.random().toString(36).substr(2, 6);
}

function getOrCreateUserId() {
  let userId = sessionStorage.getItem('scrumPokerUserId');
  if (!userId) {
    userId = generateUserId();
    sessionStorage.setItem('scrumPokerUserId', userId);
  }
  return userId;
}

function getRoomIdFromUrl() {
  return new URLSearchParams(window.location.search).get('room');
}

function isRoomUrl() {
  return getRoomIdFromUrl() !== null;
}

function toggleRoomCreationOptions(show) {
  const display = show ? 'inline-block' : 'none';
  const nameInput = document.getElementById('poker-name-input');
  if (nameInput) nameInput.style.display = display;
  const createBtn = document.getElementById('create-room-btn');
  if (createBtn) createBtn.style.display = display;
  const roomCodeInput = document.getElementById('room-code-input');
  if (roomCodeInput) roomCodeInput.style.display = display;
  const joinBtn = document.getElementById('join-room-btn');
  if (joinBtn) joinBtn.style.display = display;
}

function promptForUserName() {
  let name = '';
  while (true) {
    const input = prompt('Please enter your name (required):');
    if (input === null) return null;
    name = input.trim();
    if (name && name.length <= 20) break;
    alert(name ? 'Username must be less than 20 characters' : 'Username cannot be empty');
  }
  return name;
}

function isNewUser(userId) {
  if (!joinedUsers.has(userId)) {
    joinedUsers.add(userId);
    joinedUsernames.add(userName);
    return true;
  }
  return false;
}

const createRoomBtn = document.getElementById('create-room-btn');
if (createRoomBtn) {
  createRoomBtn.addEventListener('click', () => {
  const nameEl = document.getElementById('poker-name-input');
  const typedName = nameEl ? (nameEl.value || '').trim() : '';
  if (!typedName) {
    showToast('Please enter your name.', 'error');
    nameEl && nameEl.focus();
    return;
  }
  // Ensure a team name is assigned on room creation (localStorage-based)
  ensureTeamNameAssigned();
  sessionStorage.setItem('scrumPokerUsername', typedName);
  createRoomBtn.disabled = true;
  createRoomBtn.textContent = 'Creating...';
  fetch(APP_CONFIG.API_BASE_URL + '/api/create-room', { method: 'POST' })
    .then(response => {
      if (!response.ok) {
        return response.text().then(errorText => Promise.reject(errorText));
      }
      return response.text();
    })
    .then(roomId => {
      if (roomId && /^[a-zA-Z0-9]{8}$/.test(roomId)) {
        userId = generateUserId();
        window.location.href = `${window.location.origin}/poker?room=${roomId}`;
      } else {
        showToast('Failed to create room. Please try again.', 'error');
        createRoomBtn.disabled = false;
        createRoomBtn.innerHTML = '<span class="btn-icon">\uD83C\uDFAF</span><span class="btn-text">Create New Room</span>';
      }
    })
    .catch(error => {
      showToast(error || 'Failed to create room. Please try again.', 'error');
      createRoomBtn.disabled = false;
      createRoomBtn.innerHTML = '<span class="btn-icon">\uD83C\uDFAF</span><span class="btn-text">Create New Room</span>';
    });
  });
}

const joinRoomBtn = document.getElementById('join-room-btn');
if (joinRoomBtn) {
  joinRoomBtn.addEventListener('click', () => {
  const roomId = (document.getElementById('room-code-input').value || '').trim();
  const nameEl = document.getElementById('poker-name-input');
  const typedName = nameEl ? (nameEl.value || '').trim() : '';
  if (!roomId) {
    showToast('Please enter a valid room code.', 'error');
    return;
  }
  if (!typedName) {
    showToast('Please enter your name.', 'error');
    nameEl && nameEl.focus();
    return;
  }
  joinRoomBtn.disabled = true;
  joinRoomBtn.textContent = 'Joining...';
  fetch(APP_CONFIG.API_BASE_URL + `/api/join-room?roomId=${roomId}`, { method: 'POST' })
    .then(response => {
      if (response.ok) {
        sessionStorage.setItem('scrumPokerUsername', typedName);
        userId = generateUserId();
        window.location.href = `${window.location.origin}/poker?room=${roomId}`;
      } else {
        showToast('Room not found. Please enter a valid room code.', 'error');
        joinRoomBtn.disabled = false;
        joinRoomBtn.innerHTML = '<span class="btn-icon">\uD83C\uDFAF</span><span class="btn-text">Join</span>';
      }
    })
    .catch(error => {
      showToast('Failed to join room. Please try again.', 'error');
      joinRoomBtn.disabled = false;
      joinRoomBtn.innerHTML = '<span class="btn-icon">\uD83C\uDFAF</span><span class="btn-text">Join</span>';
    });
  });
}

const copyLinkBtn = document.getElementById('copy-link-btn');
if (copyLinkBtn) {
  copyLinkBtn.addEventListener('click', () => {
    const roomId = document.getElementById('room-code')?.textContent;
    if (roomId) {
      const link = `${window.location.origin}/poker?room=${roomId}`;
      navigator.clipboard.writeText(link)
        .then(() => showToast('Room link copied to clipboard!', 'success'))
        .catch(err => {
          showToast('Failed to copy link. Please copy manually.', 'error');
          console.error('Failed to copy link: ', err);
        });
    }
  });
}

// Set up vote button listeners (only once)
let voteButtonsSetup = false;
function setupVoteButtons() {
  const voteButtons = document.querySelectorAll('.vote-btn');
  if (voteButtons.length === 0) {
    // Buttons don't exist yet (we're on home page), will be set up when room loads
    return;
  }
  
  // Only set up listeners if not already done
  if (!voteButtonsSetup) {
    voteButtons.forEach(button => {
      button.addEventListener('click', function () {
        const roomId = document.getElementById('room-code')?.textContent;
        if (!roomId) {
          showNotification("Room ID not found. Please refresh the page.");
          return;
        }
        // Update pressed state for accessibility and visual feedback
        const allVoteBtns = document.querySelectorAll('.vote-btn');
        allVoteBtns.forEach(btn => {
          btn.classList.remove('selected');
          btn.removeAttribute('aria-pressed');
        });
        this.classList.add('selected');
        this.setAttribute('aria-pressed', 'true');
        sendVote(roomId, this.getAttribute('data-value'));
      });
    });
    voteButtonsSetup = true;
  }
  
  // Initially disable buttons until connection is established
  enableVoteButtons(false);
}

// Enable or disable vote buttons
function enableVoteButtons(enabled) {
  document.querySelectorAll('.vote-btn').forEach(button => {
    button.disabled = !enabled;
    if (enabled) {
      button.classList.remove('disabled');
    } else {
      button.classList.add('disabled');
    }
  });
}

const revealBtn = document.getElementById('reveal-votes-btn');
if (revealBtn) {
  revealBtn.addEventListener('click', () => {
    const roomId = document.getElementById('room-code')?.textContent;
    if (roomId && stompClient && stompClient.connected) {
      stompClient.send(`/app/room.${roomId}.reveal`, {}, JSON.stringify({}));
    } else {
      console.error('Not connected to WebSocket or room ID missing');
    }
  });
}

const clearBtn = document.getElementById('clear-votes-btn');
if (clearBtn) {
  clearBtn.addEventListener('click', () => {
    const roomId = document.getElementById('room-code')?.textContent;
    if (roomId && stompClient && stompClient.connected) {
      stompClient.send(`/app/room.${roomId}.clear`, {}, JSON.stringify({}));
    } else {
      console.error('Not connected to WebSocket or room ID missing');
      showToast('Please wait for connection to establish.', 'warning');
    }
  });
}

const goHomeBtn = document.getElementById('go-home-btn');
if (goHomeBtn) {
  goHomeBtn.addEventListener('click', () => {
    window.location.href = `${window.location.origin}/index.html`;
  });
}

// Home button in room view (room-info section)
const goHomeTopBtn = document.getElementById('go-home-top-btn');
if (goHomeTopBtn) {
  goHomeTopBtn.addEventListener('click', () => {
    window.location.href = `${window.location.origin}/index.html`;
  });
}

function showConnectionBanner(show) {
  let banner = document.getElementById('ws-connection-banner');
  if (show && !banner) {
    banner = document.createElement('div');
    banner.id = 'ws-connection-banner';
    banner.style.cssText = 'position:fixed;top:0;left:0;right:0;padding:8px 16px;background:#FEF2F2;color:#DC2626;text-align:center;font-size:14px;font-weight:500;z-index:10000;border-bottom:1px solid #FECACA;';
    banner.textContent = '\u26A0 Connection lost. Reconnecting...';
    document.body.prepend(banner);
  } else if (!show && banner) {
    banner.remove();
  }
}

function connectWebSocket(roomId) {
  currentRoomId = roomId;
  const socket = new SockJS(APP_CONFIG.WS_BASE_URL);
  stompClient = Stomp.over(socket);

  sessionStorage.removeItem('scrumPokerReconnected');

  // Check if user is already in a room (prevent duplicate joins)
  const storedRoomId = sessionStorage.getItem('scrumPokerRoomId');
  if (storedRoomId && storedRoomId !== roomId) {
    if (confirm('You are already in another room. Leave and join this one?')) {
      // Leave previous room
      if (stompClient && stompClient.connected) {
        stompClient.send(`/app/room.${storedRoomId}.leave`, {}, JSON.stringify({ userId }));
        stompClient.disconnect();
      }
      sessionStorage.removeItem('scrumPokerRoomId');
    } else {
      window.location.href = `${window.location.origin}/poker?room=${storedRoomId}`;
      return;
    }
  }

  // Connection timeout — show error if handshake takes too long
  const connectTimeout = setTimeout(() => {
    if (!stompClient || !stompClient.connected) {
      showNotification("Connection timed out. The server may be unavailable. Retrying...");
      try { stompClient.disconnect(); } catch (e) { /* ignore */ }
      setTimeout(() => connectWebSocket(roomId), 5000);
    }
  }, 10000);

  stompClient.connect({}, function(frame) {
    clearTimeout(connectTimeout);
    // Enable vote buttons once connected
    enableVoteButtons(true);
    showConnectionBanner(false);
    
    const roomSubscription = stompClient.subscribe(`/topic/room.${roomId}`, function(message) {
      const data = JSON.parse(message.body);

      // Check if this error is for the current user (only redirect if rejectedUserId matches)
      const isRejected = data.rejectedUserId === userId;

      if (data.error) {
        // Handle error messages (including room full)
        // Only redirect if this error is for the current user
        if (isRejected) {
          if (data.roomFull || (data.error && data.error.includes('full'))) {
            handleAccessDenied(data.error, true);
          } else {
            handleAccessDenied(data.error, false);
          }
          enableVoteButtons(false);
        } else {
          // Error for another user, but include state update if provided
          if (data.state) {
            updateUI(data.state);
          }
        }
        return;
      }

      if (data.allowed !== undefined) {
        if (!data.allowed) {
          // Only redirect if this rejection is for the current user
          if (isRejected) {
            const isRoomFull = data.roomFull || (data.error && data.error.includes('full'));
            handleAccessDenied(data.error || 'Access denied', isRoomFull);
            enableVoteButtons(false);
          } else {
            // Rejection for another user, but include state update if provided
            if (data.state) {
              updateUI(data.state);
            }
          }
          return;
        }

        sessionStorage.setItem('scrumPokerUserId', userId);
        sessionStorage.setItem('scrumPokerUsername', userName);
        sessionStorage.setItem('scrumPokerRoomId', roomId);
        enableVoteButtons(true);
      }

      if (data.state) {
        updateUI(data.state);
      } else if (data.votes && data.names) {
        updateUI(data);
      }
    });

    const voteSubscription = stompClient.subscribe(`/topic/room.${roomId}.votes`, function(message) {
      const data = JSON.parse(message.body);

      // Check if this error is for the current user
      const isRejected = data.rejectedUserId === userId;

      if (data.error) {
        // Only redirect if this error is for the current user
        if (isRejected) {
          const isRoomFull = data.roomFull || (data.error && data.error.includes('full'));
          handleAccessDenied(data.error, isRoomFull);
          enableVoteButtons(false);
        } else {
          // Error for another user, but include state update if provided
          if (data.state) {
            updateUI(data.state);
          }
        }
        return;
      }

      updateUI(data);
    });

    const isReconnect = sessionStorage.getItem('scrumPokerReconnected') === 'true';
    const endpoint = isReconnect ? `/app/reconnect.${roomId}` : `/app/join.${roomId}`;

    stompClient.send(endpoint, {}, JSON.stringify({ userId, userName }));

    if (!isReconnect) {
      sessionStorage.setItem('scrumPokerReconnected', 'true');
    }

    socket.subscriptions = { room: roomSubscription, votes: voteSubscription };

    // Immediately fetch state to render participants/votes even if initial WS payload is delayed
    fetch(APP_CONFIG.API_BASE_URL + `/api/room-state?roomId=${roomId}`)
      .then(r => r.ok ? r.json() : null)
      .then(state => { if (state) updateUI(state); })
      .catch(() => {});

  }, function(error) {
    clearTimeout(connectTimeout);
    console.error('Connection error:', error);
    enableVoteButtons(false);
    showConnectionBanner(true);
    showNotification("Connection lost - reconnecting...");
    setTimeout(() => connectWebSocket(roomId), 5000);
  });

  const disconnectHandler = () => {
    if (stompClient && stompClient.connected && userId) {
      try {
        stompClient.send(`/app/room.${roomId}.leave`, {}, JSON.stringify({ userId }));
      } catch (e) {
        // Fallback: use sendBeacon if WebSocket send fails
        navigator.sendBeacon(APP_CONFIG.API_BASE_URL + `/api/leave-room?roomId=${roomId}&userId=${userId}`);
      }
      stompClient.disconnect();
    } else if (userId) {
      // Fallback: use sendBeacon if WebSocket not connected
      navigator.sendBeacon(APP_CONFIG.API_BASE_URL + `/api/leave-room?roomId=${roomId}&userId=${userId}`);
    }
    sessionStorage.removeItem('scrumPokerRoomId');
  };

  // Enhanced disconnect handling
  window.addEventListener('beforeunload', disconnectHandler);
  window.addEventListener('pagehide', disconnectHandler);
  window.addEventListener('unload', disconnectHandler);
  
  // Handle visibility change (tab switch)
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && stompClient && stompClient.connected) {
      // Tab hidden, but keep connection alive
    }
  });

  // Start a lightweight fallback poll to keep UI in sync if any WS msg is missed
  if (statePollTimer) clearInterval(statePollTimer);
  statePollTimer = setInterval(() => {
    fetch(APP_CONFIG.API_BASE_URL + `/api/room-state?roomId=${roomId}`)
      .then(r => r.ok ? r.json() : null)
      .then(state => { if (state) updateUI(state); })
      .catch(() => {});
  }, 5000);
}

function handleAccessDenied(errorMessage, isRoomFull) {
  // Use toast notification for better UX (similar to retrospective)
  showToast(errorMessage, 'error', isRoomFull ? 5000 : 3000);

  document.querySelectorAll('.vote-btn').forEach(btn => {
    btn.disabled = true;
    btn.classList.add('disabled');
  });

  const roomInfoEl = document.getElementById('room-info');
  if (roomInfoEl) {
    var denied = document.createElement('div');
    denied.className = 'access-denied';
    var h = document.createElement('h3');
    h.textContent = isRoomFull ? 'Room Full' : 'Access Denied';
    var p = document.createElement('p');
    p.textContent = errorMessage;
    denied.appendChild(h);
    denied.appendChild(p);
    if (isRoomFull) {
      var redir = document.createElement('p');
      redir.className = 'access-denied-redirect';
      redir.textContent = 'Redirecting to home page...';
      denied.appendChild(redir);
    }
    roomInfoEl.innerHTML = '';
    roomInfoEl.appendChild(denied);
  }

  if (isRoomFull || (errorMessage && errorMessage.includes('full'))) {
    setTimeout(() => {
      window.location.href = window.location.origin;
    }, 3000);
  }
}

function updateUI(state) {
  const userCount = Object.keys(state.names || {}).length;
  
  // Update participant count elements immediately, even if user isn't in state.names yet
  const userCountElement = document.getElementById('user-count');
  if (userCountElement) {
    userCountElement.textContent = `${userCount}/10`;

    if (userCount >= 8) {
      userCountElement.classList.add('room-full-warning');
    } else {
      userCountElement.classList.remove('room-full-warning');
    }
  }

  // Update current-users count element
  const currentUsersElement = document.getElementById('current-users');
  if (currentUsersElement) {
    currentUsersElement.textContent = userCount;
  }

  // Update user-count-display element
  const userCountDisplayElement = document.getElementById('user-count-display');
  if (userCountDisplayElement) {
    userCountDisplayElement.textContent = userCount;
  }

  if (!state.names || !state.names[userId]) {
    // Not yet registered in room; wait for join confirmation/state
    // Avoid false "Access Denied" flashes during initial join or reconnect
    // But participant count is already updated above
    return;
  }

  updateUsersList(state.names);
  updateVotesList(state.votes, state.names, state.revealed);
}

function sendVote(roomId, voteValue) {
  if (!stompClient) {
    showNotification("Not connected. Please wait for connection...");
    console.error("stompClient is null - WebSocket not initialized");
    return;
  }
  
  if (!stompClient.connected) {
    showNotification("Connection lost. Reconnecting...");
    console.error("stompClient not connected - attempting to reconnect");
    // Try to reconnect
    if (currentRoomId) {
      connectWebSocket(currentRoomId);
    }
    return;
  }
  
  if (!userId || !userName) {
    showNotification("User not identified. Please refresh the page.");
    console.error("userId or userName is missing");
    return;
  }
  
  try {
    stompClient.send(`/app/vote.${roomId}`, {}, JSON.stringify({ userId, vote: voteValue, userName }));
  } catch (error) {
    console.error("Error sending vote:", error);
    showNotification("Failed to send vote. Please try again.");
  }
}

function showNotification(message) {
  const notification = document.createElement('div');
  notification.className = 'notification';
  notification.textContent = message;
  document.body.appendChild(notification);
  setTimeout(() => notification.remove(), 3000);
}

function updateUsersList(names) {
  document.getElementById('current-users').textContent = Object.keys(names).length;
  const usersList = document.getElementById('users-list');
  usersList.innerHTML = '';

  const fragment = document.createDocumentFragment();
  Object.entries(names).forEach(([entryUserId, name]) => {
    const userItem = document.createElement('li');
    userItem.className = 'user-item';
    if (entryUserId === userId) userItem.classList.add('current-user');
    if (isNewUser(entryUserId)) userItem.classList.add('new-user');
    const avatar = document.createElement('span');
    avatar.className = 'user-avatar';
    avatar.textContent = name.charAt(0).toUpperCase();
    const nameSpan = document.createElement('span');
    nameSpan.className = 'user-name';
    nameSpan.textContent = name;
    userItem.appendChild(avatar);
    userItem.appendChild(nameSpan);
    fragment.appendChild(userItem);
  });

  usersList.appendChild(fragment);
  document.getElementById('users-section').style.display = 'block';
}

function updateVotesList(votes, names, revealed) {
  const votesList = document.getElementById('votes-list');
  votesList.innerHTML = '';
  const votesContainer = document.getElementById('votes-section');
  votesContainer.classList.toggle('votes-revealed', revealed);

  for (const [visUserId, vote] of Object.entries(votes)) {
    const userName = names[visUserId] || "Anonymous";
    const voteItem = document.createElement('li');
    const voterName = document.createElement('span');
    voterName.className = 'voter-name';
    voterName.textContent = userName;
    const voteDiv = document.createElement('div');
    const votedLabel = document.createElement('span');
    votedLabel.textContent = 'Voted';
    const votedNumber = document.createElement('span');
    votedNumber.className = 'voted-number';
    votedNumber.textContent = revealed ? vote : '?';
    voteDiv.appendChild(votedLabel);
    voteDiv.appendChild(votedNumber);
    voteItem.appendChild(voterName);
    voteItem.appendChild(voteDiv);
    votesList.appendChild(voteItem);
  }

  // Vote summary (average, min, max, consensus)
  var summaryEl = document.getElementById('vote-summary');
  if (summaryEl) {
    if (revealed && Object.keys(votes).length > 0) {
      var numericVotes = Object.values(votes).filter(function (v) { return !isNaN(Number(v)); }).map(Number);
      if (numericVotes.length > 0) {
        var avg = numericVotes.reduce(function (a, b) { return a + b; }, 0) / numericVotes.length;
        var min = Math.min.apply(null, numericVotes);
        var max = Math.max.apply(null, numericVotes);
        var allSame = numericVotes.every(function (v) { return v === numericVotes[0]; });
        var spread = max - min;

        document.getElementById('vote-avg').textContent = avg % 1 === 0 ? avg : avg.toFixed(1);
        document.getElementById('vote-min').textContent = min;
        document.getElementById('vote-max').textContent = max;

        var consensusEl = document.getElementById('vote-consensus');
        if (allSame) {
          consensusEl.textContent = 'Perfect!';
          consensusEl.className = 'vote-summary-value vote-consensus-perfect';
        } else if (spread <= 2) {
          consensusEl.textContent = 'Close';
          consensusEl.className = 'vote-summary-value vote-consensus-close';
        } else {
          consensusEl.textContent = 'Discuss';
          consensusEl.className = 'vote-summary-value vote-consensus-discuss';
        }
        summaryEl.style.display = 'flex';
      } else {
        // All votes are non-numeric (coffee etc)
        summaryEl.style.display = 'none';
      }
    } else {
      summaryEl.style.display = 'none';
    }
  }

  votesContainer.style.display = 'block';
}

window.onload = function() {
  if (isRoomUrl()) {
    const roomId = getRoomIdFromUrl();
    userId = getOrCreateUserId();
    userName = sessionStorage.getItem('scrumPokerUsername') || promptForUserName();
    if (!userName) {
      window.location.href = `${window.location.origin}/index.html`;
      return;
    }
    sessionStorage.setItem('scrumPokerUsername', userName);
    document.getElementById('room-code').textContent = roomId;
    document.getElementById('user-name').textContent = userName;
    document.getElementById('room-info').style.display = 'block';
    updateTeamNameUI();
    document.getElementById('voting-section').style.display = 'block';
    
    // Hide room creation section and rules section when user enters a room
    const roomSection = document.getElementById('room-section');
    if (roomSection) roomSection.style.display = 'none';
    const pokerRulesSection = document.getElementById('poker-rules-section');
    if (pokerRulesSection) pokerRulesSection.style.display = 'none';
    
    toggleRoomCreationOptions(false);
    document.getElementById('go-home-btn').style.display = 'inline-block';
    // Set up vote buttons now that voting section is visible
    setupVoteButtons();
    connectWebSocket(roomId);
  } else {
    // Show room creation section and rules section on home page
    const roomSection = document.getElementById('room-section');
    if (roomSection) roomSection.style.display = 'block';
    const pokerRulesSection = document.getElementById('poker-rules-section');
    if (pokerRulesSection) pokerRulesSection.style.display = 'block';
    
    toggleRoomCreationOptions(true);
    const saved = sessionStorage.getItem('scrumPokerUsername') || '';
    const nameEl = document.getElementById('poker-name-input');
    if (nameEl && saved) nameEl.value = saved;
    const nameInput = document.getElementById('poker-name-input');
    if (nameInput) {
      nameInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          document.getElementById('create-room-btn').click();
        }
      });
    }
    const roomCodeInput = document.getElementById('room-code-input');
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

window.addEventListener('beforeunload', function() {
  if (currentRoomId && userId) {
    navigator.sendBeacon(APP_CONFIG.API_BASE_URL + `/api/leave-room?roomId=${currentRoomId}&userId=${userId}`);
  }
});

document.addEventListener('keydown', function(e) {
  if ((e.key === 'F5') || (e.key === 'r' && e.ctrlKey) || (e.key === 'R' && e.ctrlKey && e.shiftKey)) {
    e.preventDefault();
    showToast('Refresh is disabled - use the navigation buttons instead');
  }
});

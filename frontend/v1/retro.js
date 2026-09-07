/**
 * Copyright (c) 2026 rebcoder. MIT licensed — see LICENSE.
 * 
 * Sprint Retrospective - JavaScript Logic
 * Handles board creation, card management, voting, and real-time collaboration
 */

let retroStompClient = null;
let retroUserId = null;
let retroUserName = null;
let retroSortEnabled = false;
let retroSearchQuery = '';
let retroCurrentState = null;

// Styled confirm dialog (replaces native confirm())
function showConfirmDialog(message, confirmLabel, onConfirm) {
  const backdrop = document.getElementById('confirm-dialog-backdrop');
  const msgEl = document.getElementById('confirm-dialog-message');
  const cancelBtn = document.getElementById('confirm-dialog-cancel');
  const confirmBtn = document.getElementById('confirm-dialog-confirm');
  if (!backdrop) { if (confirm(message)) onConfirm(); return; }

  msgEl.textContent = message;
  confirmBtn.querySelector('.btn-text').textContent = confirmLabel || 'Delete';
  backdrop.style.display = 'flex';

  var removeFocusTrap = null;
  if (typeof trapFocus === 'function') {
    removeFocusTrap = trapFocus(backdrop.querySelector('.confirm-dialog'));
  }

  function cleanup() {
    backdrop.style.display = 'none';
    if (removeFocusTrap) removeFocusTrap();
    cancelBtn.removeEventListener('click', handleCancel);
    confirmBtn.removeEventListener('click', handleConfirm);
    backdrop.removeEventListener('click', handleBackdrop);
    document.removeEventListener('keydown', handleEsc);
  }
  function handleCancel() { cleanup(); }
  function handleConfirm() { cleanup(); onConfirm(); }
  function handleBackdrop(e) { if (e.target === backdrop) cleanup(); }
  function handleEsc(e) { if (e.key === 'Escape') cleanup(); }

  cancelBtn.addEventListener('click', handleCancel);
  confirmBtn.addEventListener('click', handleConfirm);
  backdrop.addEventListener('click', handleBackdrop);
  document.addEventListener('keydown', handleEsc);
  cancelBtn.focus();
}

// Team name utilities loaded from shared.js
// Aliases for backward compat within this file
var retroGetStoredTeamName = getStoredTeamName;
var retroEnsureTeamNameAssigned = ensureTeamNameAssigned;

function retroUpdateTeamNameUI() {
  const teamNameEl = document.getElementById('retro-team-name');
  if (!teamNameEl) return;
  teamNameEl.textContent = retroGetStoredTeamName() || 'Team';
}

// Enhanced toast notification system
function retroShowToast(message, type = 'info', duration = 3000) {
  const container = document.getElementById('retro-toast-container') || document.body;
  
  // Toast type icons (Lucide-style)
  const typeIcons = {
    success: 'check-circle',
    error: 'circle-x',
    warning: 'alert-triangle',
    info: 'info'
  };

  const toastEl = document.createElement('div');
  toastEl.className = `toast-enhanced toast-${type}`;
  toastEl.innerHTML = `
    <div class="toast-icon ic" data-ic="${typeIcons[type] || typeIcons.info}" aria-hidden="true"></div>
    <div class="toast-content">${message}</div>
    <button class="toast-close" aria-label="Close notification"><span class="ic" data-ic="x" aria-hidden="true"></span></button>
  `;

  container.appendChild(toastEl);
  if (typeof window.hydrateIcons === 'function') window.hydrateIcons(toastEl);
  
  // Close button functionality
  const closeBtn = toastEl.querySelector('.toast-close');
  closeBtn.addEventListener('click', () => {
    retroRemoveToast(toastEl);
  });
  
  // Auto-remove after duration
  setTimeout(() => {
    retroRemoveToast(toastEl);
  }, duration);
  
  return toastEl;
}

// Expose as window.showToast so shared.js can use a single name
window.showToast = retroShowToast;

function retroRemoveToast(toastEl) {
  if (!toastEl || !toastEl.parentNode) return;
  
  toastEl.classList.add('toast-exit');
  setTimeout(() => {
    if (toastEl && toastEl.parentNode) {
      toastEl.parentNode.removeChild(toastEl);
    }
  }, 300);
}

function retroGenerateUserId() {
  return Math.random().toString(36).substr(2, 6);
}

function retroGetRoomIdFromUrl() {
  return new URLSearchParams(window.location.search).get('room');
}

// Beautiful modal for name input when joining through link
function retroPromptForUserName() {
  const input = document.getElementById('retro-name-input');
  if (input) {
    const value = (input.value || '').trim();
    if (value && value.length <= 20) return value;
  }
  
  return new Promise((resolve) => {
    retroShowNameInputModal(resolve);
  });
}

function retroShowNameInputModal(callback) {
  const modal = document.getElementById('retro-name-modal');
  const modalInput = document.getElementById('retro-modal-name-input');
  const modalError = document.getElementById('retro-modal-name-error');
  const continueBtn = document.getElementById('retro-modal-continue');
  const cancelBtn = document.getElementById('retro-modal-cancel');
  
  // Reset modal state
  modalInput.value = '';
  modalError.style.display = 'none';
  continueBtn.disabled = false;
  continueBtn.classList.remove('modal-btn-loading');
  
  // Show modal
  modal.style.display = 'flex';

  // Focus trap + focus input
  let removeFocusTrap = null;
  setTimeout(() => {
    if (typeof trapFocus === 'function') {
      removeFocusTrap = trapFocus(modal);
    }
    modalInput.focus();
  }, 100);

  function hideModal() {
    modal.style.display = 'none';
    if (removeFocusTrap) removeFocusTrap();
  }
  
  function validateAndContinue() {
    const name = modalInput.value.trim();
    
    if (!name) {
      modalError.textContent = 'Please enter your name';
      modalError.style.display = 'block';
      modalInput.focus();
      modalInput.classList.add('error-shake');
      setTimeout(() => modalInput.classList.remove('error-shake'), 500);
      return;
    }
    
    if (name.length > 20) {
      modalError.textContent = 'Name must be 20 characters or less';
      modalError.style.display = 'block';
      modalInput.focus();
      modalInput.classList.add('error-shake');
      setTimeout(() => modalInput.classList.remove('error-shake'), 500);
      return;
    }
    
    // Add loading state
    continueBtn.disabled = true;
    continueBtn.classList.add('modal-btn-loading');
    
    // Simulate brief loading for better UX
    setTimeout(() => {
      hideModal();
      callback(name);
    }, 300);
  }
  
  function handleCancel() {
    hideModal();
    callback(null);
  }
  
  // Event listeners
  continueBtn.onclick = validateAndContinue;
  cancelBtn.onclick = handleCancel;
  
  modalInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      validateAndContinue();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      handleCancel();
    }
  });
  
  // Clear error when user types
  modalInput.addEventListener('input', () => {
    modalError.style.display = 'none';
  });
  
  // Close modal when clicking backdrop
  modal.addEventListener('click', (e) => {
    if (e.target === modal) {
      handleCancel();
    }
  });
}

function retroShowNameError(show = true) {
  const errorEl = document.getElementById('retro-name-error');
  if (errorEl) {
    errorEl.style.display = show ? 'block' : 'none';
  }
}

function retroShowJoinError(show = true) {
  const errorEl = document.getElementById('retro-join-error');
  if (errorEl) {
    errorEl.style.display = show ? 'block' : 'none';
  }
}

// Setup event listeners when DOM is ready
function retroSetupEventListeners() {
  const createBtn = document.getElementById('retro-create-room-btn');
  const joinBtn = document.getElementById('retro-join-room-btn');
  const copyLinkBtn = document.getElementById('retro-copy-link-btn');
  const exportPdfBtn = document.getElementById('retro-export-pdf-btn');
  const goHomeBtn = document.getElementById('retro-go-home-btn');
  const goHomeTopBtn = document.getElementById('retro-go-home-top-btn');

  if (createBtn) {
    createBtn.addEventListener('click', () => {
      const nameEl = document.getElementById('retro-name-input');
      const typedName = nameEl ? (nameEl.value || '').trim() : '';
      
      // Hide previous errors
      retroShowNameError(false);
      
      if (!typedName) {
        retroShowNameError(true);
        nameEl && nameEl.focus();
        return;
      }
      // Ensure a team name is assigned on board creation (localStorage-based)
      retroEnsureTeamNameAssigned();
      sessionStorage.setItem('retroUserName', typedName);
      fetch(APP_CONFIG.API_BASE_URL + '/api/retro/create', { method: 'POST' })
        .then(response => {
          if (!response.ok) {
            return response.text().then(errorText => Promise.reject(errorText));
          }
          return response.text();
        })
        .then(roomId => {
          if (roomId && /^[a-zA-Z0-9]{8}$/.test(roomId)) {
            window.location.href = `${window.location.origin}/retro?room=${roomId}`;
          } else {
            retroShowToast('Failed to create room. Please try again.', 'error', 4000);
          }
        })
        .catch(error => {
          retroShowToast(error || 'Failed to create room. Please try again.', 'error', 4000);
        });
    });
  }

  if (joinBtn) {
    joinBtn.addEventListener('click', () => {
      const roomId = document.getElementById('retro-room-code-input').value.trim();
      const nameEl = document.getElementById('retro-name-input');
      const typedName = nameEl ? (nameEl.value || '').trim() : '';
      
      // Hide previous errors
      retroShowNameError(false);
      retroShowJoinError(false);
      
      if (!typedName) {
        retroShowNameError(true);
        nameEl && nameEl.focus();
        return;
      }
      
      if (!roomId) {
        retroShowJoinError(true);
        document.getElementById('retro-room-code-input').focus();
        return;
      }
      
      fetch(APP_CONFIG.API_BASE_URL + `/api/retro/join?roomId=${roomId}`, { method: 'POST' })
        .then(res => {
          if (res.ok) {
            sessionStorage.setItem('retroUserName', typedName);
            window.location.href = `${window.location.origin}/retro?room=${roomId}`;
          } else {
            retroShowJoinError(true);
          }
        })
        .catch(err => {
          console.error('Error joining room:', err);
          retroShowToast('Failed to join room. Please try again.', 'error', 4000);
        });
    });
  }

  if (goHomeBtn) {
    goHomeBtn.addEventListener('click', () => {
      window.location.href = `${window.location.origin}/index.html`;
    });
  }

  if (copyLinkBtn) {
    copyLinkBtn.addEventListener('click', () => {
      const roomId = document.getElementById('retro-room-code')?.textContent;
      if (roomId) {
        const link = `${window.location.origin}/retro?room=${roomId}`;
        navigator.clipboard.writeText(link).then(() => retroShowToast('Invite link copied'));
      }
    });
  }

  if (exportPdfBtn) {
    exportPdfBtn.addEventListener('click', () => {
      retroExportToPDF();
    });
  }

  if (goHomeTopBtn) {
    goHomeTopBtn.addEventListener('click', () => {
      window.location.href = `${window.location.origin}/index.html`;
    });
  }
}

// Initialize event listeners when DOM is ready
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', retroSetupEventListeners);
} else {
  retroSetupEventListeners();
}

async function retroExportToPDF() {
  if (!retroCurrentState) {
    retroShowToast('No board data to export', 'error');
    return;
  }

  const exportBtn = document.getElementById('retro-export-pdf-btn');
  const originalText = exportBtn ? exportBtn.innerHTML : '';
  if (exportBtn) {
    exportBtn.disabled = true;
    exportBtn.innerHTML = '<span class="btn-icon">⏳</span><span class="btn-text">Generating...</span>';
  }

  try {
    retroShowToast('Generating PDF... 📄', 'info', 2000);
    
    // Wait and check for jsPDF library with retries
    let jsPDF;
    let attempts = 0;
    const maxAttempts = 10;
    
    while (attempts < maxAttempts && !jsPDF) {
      await new Promise(resolve => setTimeout(resolve, 200));
      
      // Debug: log what's available
      if (attempts === 0) {
        console.log('Checking for jsPDF:', {
          'window.jsPDF': typeof window.jsPDF,
          'window.jspdf': typeof window.jspdf,
          'window.jspdf.jsPDF': typeof window.jspdf?.jsPDF,
          'jspdf': typeof jspdf
        });
      }
      
      // Check for jsPDF in different possible locations
      if (typeof window.jsPDF !== 'undefined') {
        jsPDF = window.jsPDF;
        console.log('Found jsPDF via window.jsPDF');
        break;
      } else if (typeof window.jspdf !== 'undefined' && window.jspdf.jsPDF) {
        jsPDF = window.jspdf.jsPDF;
        // Normalize for future use
        window.jsPDF = jsPDF;
        console.log('Found jsPDF via window.jspdf.jsPDF');
        break;
      }
      
      attempts++;
    }
    
    if (!jsPDF) {
      // Try to load it manually as last resort
      console.warn('jsPDF not found, attempting manual load...');
      try {
        await new Promise((resolve, reject) => {
          // Check if script is already in the page
          const existingScript = document.getElementById('jspdf-script');
          if (existingScript) {
            // Check if it's already loaded
            if (existingScript.dataset.loaded === 'true') {
              // Already tried, check one more time
              if (window.jspdf && window.jspdf.jsPDF) {
                window.jsPDF = window.jspdf.jsPDF;
                jsPDF = window.jsPDF;
              }
              resolve();
              return;
            }
            
            // Wait for script to load
            const checkLoaded = () => {
              if (window.jspdf && window.jspdf.jsPDF) {
                window.jsPDF = window.jspdf.jsPDF;
                jsPDF = window.jsPDF;
                existingScript.dataset.loaded = 'true';
                resolve();
              } else {
                reject(new Error('jsPDF script loaded but not accessible'));
              }
            };
            
            if (existingScript.complete || existingScript.readyState === 'complete') {
              setTimeout(checkLoaded, 100);
            } else {
              existingScript.addEventListener('load', () => setTimeout(checkLoaded, 100));
              existingScript.addEventListener('error', () => reject(new Error('Failed to load jsPDF script from CDN')));
              // Timeout after 5 seconds
              setTimeout(() => reject(new Error('jsPDF script load timeout')), 5000);
            }
          } else {
            // Script tag absent: load the vendored copy from our own origin.
            // Never point this at a CDN — the CSP forbids third-party script origins.
            const script = document.createElement('script');
            script.src = 'vendor/jspdf-2.5.1.umd.min.js';
            script.id = 'jspdf-script-fallback';
            script.crossOrigin = 'anonymous';
            script.onload = () => {
              setTimeout(() => {
                if (window.jspdf && window.jspdf.jsPDF) {
                  window.jsPDF = window.jspdf.jsPDF;
                  jsPDF = window.jsPDF;
                  resolve();
                } else {
                  reject(new Error('jsPDF loaded from fallback but not accessible'));
                }
              }, 100);
            };
            script.onerror = () => reject(new Error('Failed to load jsPDF from fallback CDN'));
            document.head.appendChild(script);
            setTimeout(() => reject(new Error('Fallback jsPDF load timeout')), 5000);
          }
        });
      } catch (loadError) {
        console.error('Error loading jsPDF:', loadError);
        throw new Error('jsPDF library not available. Please check your internet connection and refresh the page. Error: ' + loadError.message);
      }
    }
    
    if (!jsPDF) {
      throw new Error('jsPDF library not available. Please check your internet connection and refresh the page.');
    }
    
    const pdf = new jsPDF({
      orientation: 'portrait',
      unit: 'mm',
      format: 'a4'
    });

    const pageWidth = pdf.internal.pageSize.getWidth();
    const pageHeight = pdf.internal.pageSize.getHeight();
    const margin = 15;
    const contentWidth = pageWidth - (margin * 2);
    const cardHeight = 30; // Increased for better spacing
    const cardSpacing = 8; // Increased spacing between cards

    // Get team members
    const teamMembersList = document.getElementById('retro-team-members-list');
    let teamMembers = [];
    if (teamMembersList && teamMembersList.style.display !== 'none') {
      const memberBadges = teamMembersList.querySelectorAll('.team-member-badge');
      teamMembers = Array.from(memberBadges).map(badge => badge.textContent.trim());
    }
    if (teamMembers.length === 0) {
      teamMembers = ['Team Members'];
    }

    // Helper function to add gradient background
    function addGradientBackground(pageNum) {
      const colors = [
        [102, 126, 234, 118, 75, 162], // Page 1: Purple gradient (front page)
        [16, 185, 129, 5, 150, 105],   // Page 2: Green gradient (went well)
        [245, 158, 11, 217, 119, 6],   // Page 3: Orange gradient (to improve)
        [59, 130, 246, 29, 78, 216]    // Page 4: Blue gradient (action items)
      ];
      const [r1, g1, b1, r2, g2, b2] = colors[pageNum - 1] || colors[0];
      
      // Draw gradient in chunks for better performance
      const chunkSize = 5;
      for (let y = 0; y < pageHeight; y += chunkSize) {
        const ratio = y / pageHeight;
        const r = Math.round(r1 + (r2 - r1) * ratio);
        const g = Math.round(g1 + (g2 - g1) * ratio);
        const b = Math.round(b1 + (b2 - b1) * ratio);
        pdf.setFillColor(r, g, b);
        pdf.rect(0, y, pageWidth, chunkSize, 'F');
      }
    }

    // Helper function to add decorative border
    function addDecorativeBorder() {
      pdf.setDrawColor(255, 255, 255);
      pdf.setLineWidth(3);
      pdf.rect(margin - 5, margin - 5, contentWidth + 10, pageHeight - (margin * 2) + 10);
      pdf.setLineWidth(1);
      pdf.setDrawColor(0, 0, 0, 20);
      pdf.rect(margin - 3, margin - 3, contentWidth + 6, pageHeight - (margin * 2) + 6);
    }

    // Get current date for filename
    const now = new Date();

    // PAGE 1: Front Page (use the page that jsPDF creates automatically)
    addGradientBackground(1);
    addDecorativeBorder();

    // Title
    pdf.setTextColor(255, 255, 255);
    pdf.setFontSize(36);
    pdf.setFont('helvetica', 'bold');
    pdf.text('Sprint Retrospective', pageWidth / 2, margin + 40, { align: 'center' });

    // Decorative text instead of emoji
    pdf.setFontSize(24);
    pdf.setFont('helvetica', 'normal');
    pdf.text('Sprint Retrospective Report', pageWidth / 2, margin + 65, { align: 'center' });

    // Date and Time
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

    pdf.setFontSize(18);
    pdf.setFont('helvetica', 'normal');
    pdf.text('Date:', pageWidth / 2, margin + 90, { align: 'center' });
    pdf.setFontSize(16);
    pdf.text(dateStr, pageWidth / 2, margin + 100, { align: 'center' });
    pdf.text(timeStr, pageWidth / 2, margin + 110, { align: 'center' });

    // Team Members Section
    pdf.setFontSize(24);
    pdf.setFont('helvetica', 'bold');
    pdf.text('Team Members', pageWidth / 2, margin + 140, { align: 'center' });

    pdf.setFontSize(16);
    pdf.setFont('helvetica', 'normal');
    let yPos = margin + 155;
    teamMembers.forEach((member, index) => {
      if (yPos > pageHeight - margin - 20) {
        return;
      }
      pdf.text(`• ${member}`, pageWidth / 2, yPos, { align: 'center' });
      yPos += 10;
    });

    // PAGE 2: What Went Well
    pdf.addPage();
    addGradientBackground(2);
    addDecorativeBorder();

    // Section Header
    pdf.setTextColor(255, 255, 255);
    pdf.setFontSize(32);
    pdf.setFont('helvetica', 'bold');
    pdf.text('What Went Well', pageWidth / 2, margin + 35, { align: 'center' });
    pdf.setFontSize(14);
    pdf.setFont('helvetica', 'normal');
    pdf.text('Positive aspects and successes', pageWidth / 2, margin + 45, { align: 'center' });

    // Cards
    const wentWellCards = retroCurrentState.wentWell || [];
    let currentY = margin + 55;

    if (wentWellCards.length === 0) {
      pdf.setFontSize(14);
      pdf.setFont('helvetica', 'italic');
      pdf.text('No items in this section', pageWidth / 2, currentY + 20, { align: 'center' });
    } else {
      wentWellCards.forEach((card, index) => {
        if (currentY + cardHeight > pageHeight - margin - 30) {
          // Start new page if needed
          pdf.addPage();
          addGradientBackground(2);
          addDecorativeBorder();
          pdf.setFontSize(32);
          pdf.setFont('helvetica', 'bold');
          pdf.text('What Went Well (continued)', pageWidth / 2, margin + 25, { align: 'center' });
          currentY = margin + 40;
        }

        // Card background with shadow effect
        const cardY = currentY - 8;
        const cardX = margin;
        
        // Shadow effect (darker rectangle slightly offset)
        pdf.setFillColor(0, 0, 0, 20);
        pdf.rect(cardX + 2, cardY + 2, contentWidth, cardHeight, 'F');
        
        // Main card background - white with slight gradient effect
        pdf.setFillColor(255, 255, 255);
        pdf.setDrawColor(16, 185, 129);
        pdf.setLineWidth(2.5);
        
        // Left border accent (thicker colored line)
        pdf.setFillColor(16, 185, 129);
        pdf.rect(cardX, cardY, 4, cardHeight, 'F');
        
        // Card body
        pdf.setFillColor(255, 255, 255);
        pdf.setDrawColor(16, 185, 129);
        if (pdf.roundedRect) {
          pdf.roundedRect(cardX, cardY, contentWidth, cardHeight, 3, 3, 'FD');
        } else {
          pdf.rect(cardX, cardY, contentWidth, cardHeight, 'FD');
        }

        // Card text with better styling
        pdf.setTextColor(30, 41, 59);
        pdf.setFontSize(12);
        pdf.setFont('helvetica', 'bold');
        const cardText = card.text || 'No text';
        const lines = pdf.splitTextToSize(cardText, contentWidth - 30);
        let textY = currentY + 3;
        lines.forEach((line, lineIndex) => {
          if (textY < currentY + cardHeight - 10) {
            pdf.text(line, margin + 15, textY);
            textY += 6;
          }
        });

        // Card metadata with text labels - improved visibility
        pdf.setFontSize(10);
        pdf.setTextColor(60, 70, 90); // Darker color for better visibility
        pdf.setFont('helvetica', 'normal');
        
        // Author name with better styling
        pdf.text('Author:', margin + 15, currentY + cardHeight - 7);
        pdf.setFont('helvetica', 'bold');
        pdf.setTextColor(30, 41, 59); // Even darker for author name
        pdf.text(card.authorName || 'Unknown', margin + 35, currentY + cardHeight - 7);
        
        // Votes with better styling
        pdf.setFont('helvetica', 'normal');
        pdf.setTextColor(60, 70, 90);
        pdf.text('Votes:', pageWidth - margin - 40, currentY + cardHeight - 7, { align: 'right' });
        pdf.setFont('helvetica', 'bold');
        pdf.setTextColor(30, 41, 59);
        pdf.text(String(card.votes || 0), pageWidth - margin - 15, currentY + cardHeight - 7, { align: 'right' });

        currentY += cardHeight + cardSpacing;
      });
    }

    // PAGE 3: To Improve
    pdf.addPage();
    addGradientBackground(3);
    addDecorativeBorder();

    // Section Header
    pdf.setTextColor(255, 255, 255);
    pdf.setFontSize(32);
    pdf.setFont('helvetica', 'bold');
    pdf.text('Improve', pageWidth / 2, margin + 35, { align: 'center' });
    pdf.setFontSize(14);
    pdf.setFont('helvetica', 'normal');
    pdf.text('Areas for improvement and challenges', pageWidth / 2, margin + 45, { align: 'center' });

    // Cards
    const toImproveCards = retroCurrentState.toImprove || [];
    currentY = margin + 55;

    if (toImproveCards.length === 0) {
      pdf.setFontSize(14);
      pdf.setFont('helvetica', 'italic');
      pdf.text('No items in this section', pageWidth / 2, currentY + 20, { align: 'center' });
    } else {
      toImproveCards.forEach((card, index) => {
        if (currentY + cardHeight > pageHeight - margin - 30) {
          pdf.addPage();
          addGradientBackground(3);
          addDecorativeBorder();
          pdf.setFontSize(32);
          pdf.setFont('helvetica', 'bold');
          pdf.text('Improve (continued)', pageWidth / 2, margin + 25, { align: 'center' });
          currentY = margin + 40;
        }

        // Card background with shadow effect
        const cardY = currentY - 8;
        const cardX = margin;
        
        // Shadow effect (darker rectangle slightly offset)
        pdf.setFillColor(0, 0, 0, 20);
        pdf.rect(cardX + 2, cardY + 2, contentWidth, cardHeight, 'F');
        
        // Main card background - white with slight gradient effect
        pdf.setFillColor(255, 255, 255);
        pdf.setDrawColor(245, 158, 11);
        pdf.setLineWidth(2.5);
        
        // Left border accent (thicker colored line)
        pdf.setFillColor(245, 158, 11);
        pdf.rect(cardX, cardY, 4, cardHeight, 'F');
        
        // Card body
        pdf.setFillColor(255, 255, 255);
        pdf.setDrawColor(245, 158, 11);
        if (pdf.roundedRect) {
          pdf.roundedRect(cardX, cardY, contentWidth, cardHeight, 3, 3, 'FD');
        } else {
          pdf.rect(cardX, cardY, contentWidth, cardHeight, 'FD');
        }

        // Card text with better styling
        pdf.setTextColor(30, 41, 59);
        pdf.setFontSize(12);
        pdf.setFont('helvetica', 'bold');
        const cardText = card.text || 'No text';
        const lines = pdf.splitTextToSize(cardText, contentWidth - 30);
        let textY = currentY + 3;
        lines.forEach((line, lineIndex) => {
          if (textY < currentY + cardHeight - 10) {
            pdf.text(line, margin + 15, textY);
            textY += 6;
          }
        });

        // Card metadata with text labels - improved visibility
        pdf.setFontSize(10);
        pdf.setTextColor(60, 70, 90); // Darker color for better visibility
        pdf.setFont('helvetica', 'normal');
        
        // Author name with better styling
        pdf.text('Author:', margin + 15, currentY + cardHeight - 7);
        pdf.setFont('helvetica', 'bold');
        pdf.setTextColor(30, 41, 59); // Even darker for author name
        pdf.text(card.authorName || 'Unknown', margin + 35, currentY + cardHeight - 7);
        
        // Votes with better styling
        pdf.setFont('helvetica', 'normal');
        pdf.setTextColor(60, 70, 90);
        pdf.text('Votes:', pageWidth - margin - 40, currentY + cardHeight - 7, { align: 'right' });
        pdf.setFont('helvetica', 'bold');
        pdf.setTextColor(30, 41, 59);
        pdf.text(String(card.votes || 0), pageWidth - margin - 15, currentY + cardHeight - 7, { align: 'right' });

        currentY += cardHeight + cardSpacing;
      });
    }

    // PAGE 4: Action Items
    pdf.addPage();
    addGradientBackground(4);
    addDecorativeBorder();

    // Section Header
    pdf.setTextColor(255, 255, 255);
    pdf.setFontSize(32);
    pdf.setFont('helvetica', 'bold');
    pdf.text('Improvement Actions', pageWidth / 2, margin + 35, { align: 'center' });
    pdf.setFontSize(14);
    pdf.setFont('helvetica', 'normal');
    pdf.text('Tasks and commitments for next sprint', pageWidth / 2, margin + 45, { align: 'center' });

    // Cards
    const actionItemsCards = retroCurrentState.actionItems || [];
    currentY = margin + 55;

    if (actionItemsCards.length === 0) {
      pdf.setFontSize(14);
      pdf.setFont('helvetica', 'italic');
      pdf.text('No items in this section', pageWidth / 2, currentY + 20, { align: 'center' });
    } else {
      actionItemsCards.forEach((card, index) => {
        if (currentY + cardHeight > pageHeight - margin - 30) {
          pdf.addPage();
          addGradientBackground(4);
          addDecorativeBorder();
          pdf.setFontSize(32);
          pdf.setFont('helvetica', 'bold');
          pdf.text('Improvement Actions (continued)', pageWidth / 2, margin + 25, { align: 'center' });
          currentY = margin + 40;
        }

        // Card background with shadow effect
        const cardY = currentY - 8;
        const cardX = margin;
        
        // Shadow effect (darker rectangle slightly offset)
        pdf.setFillColor(0, 0, 0, 20);
        pdf.rect(cardX + 2, cardY + 2, contentWidth, cardHeight, 'F');
        
        // Main card background - white with slight gradient effect
        pdf.setFillColor(255, 255, 255);
        pdf.setDrawColor(59, 130, 246);
        pdf.setLineWidth(2.5);
        
        // Left border accent (thicker colored line)
        pdf.setFillColor(59, 130, 246);
        pdf.rect(cardX, cardY, 4, cardHeight, 'F');
        
        // Card body
        pdf.setFillColor(255, 255, 255);
        pdf.setDrawColor(59, 130, 246);
        if (pdf.roundedRect) {
          pdf.roundedRect(cardX, cardY, contentWidth, cardHeight, 3, 3, 'FD');
        } else {
          pdf.rect(cardX, cardY, contentWidth, cardHeight, 'FD');
        }

        // Card text with better styling
        pdf.setTextColor(30, 41, 59);
        pdf.setFontSize(12);
        pdf.setFont('helvetica', 'bold');
        const cardText = card.text || 'No text';
        const lines = pdf.splitTextToSize(cardText, contentWidth - 30);
        let textY = currentY + 3;
        lines.forEach((line, lineIndex) => {
          if (textY < currentY + cardHeight - 10) {
            pdf.text(line, margin + 15, textY);
            textY += 6;
          }
        });

        // Card metadata with text labels - improved visibility
        pdf.setFontSize(10);
        pdf.setTextColor(60, 70, 90); // Darker color for better visibility
        pdf.setFont('helvetica', 'normal');
        
        // Author name with better styling
        pdf.text('Author:', margin + 15, currentY + cardHeight - 7);
        pdf.setFont('helvetica', 'bold');
        pdf.setTextColor(30, 41, 59); // Even darker for author name
        pdf.text(card.authorName || 'Unknown', margin + 35, currentY + cardHeight - 7);
        
        // Votes with better styling
        pdf.setFont('helvetica', 'normal');
        pdf.setTextColor(60, 70, 90);
        pdf.text('Votes:', pageWidth - margin - 40, currentY + cardHeight - 7, { align: 'right' });
        pdf.setFont('helvetica', 'bold');
        pdf.setTextColor(30, 41, 59);
        pdf.text(String(card.votes || 0), pageWidth - margin - 15, currentY + cardHeight - 7, { align: 'right' });

        currentY += cardHeight + cardSpacing;
      });
    }

    // Save PDF
    const roomId = document.getElementById('retro-room-code')?.textContent || 'retro';
    const fileName = `retrospective-${roomId}-${now.toISOString().split('T')[0]}.pdf`;
    pdf.save(fileName);

    retroShowToast('PDF exported successfully! 🎉', 'success', 3000);
  } catch (error) {
    console.error('Error generating PDF:', error);
    retroShowToast('Failed to generate PDF. Please try again.', 'error', 4000);
  } finally {
    if (exportBtn) {
      exportBtn.disabled = false;
      exportBtn.innerHTML = originalText;
    }
  }
}

function retroShowConnectionBanner(show) {
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

function retroConnect(roomId) {
  // Check if user is already in a retro board (prevent duplicate joins)
  const currentRetroRoomId = sessionStorage.getItem('retroRoomId');
  if (currentRetroRoomId && currentRetroRoomId !== roomId) {
    showConfirmDialog('You are already in another retrospective board. Leave and join this one?', 'Switch Board', () => {
      if (retroStompClient && retroStompClient.connected) {
        retroStompClient.send(`/app/retro.leave.${currentRetroRoomId}`, {}, JSON.stringify({ userId: retroUserId }));
        retroStompClient.disconnect();
      }
      sessionStorage.removeItem('retroRoomId');
      retroConnect(roomId);
    });
    return;
  }

  const socket = new SockJS(APP_CONFIG.WS_BASE_URL);
  retroStompClient = Stomp.over(socket);
  retroStompClient.connect({}, function() {
    retroShowConnectionBanner(false);
    retroStompClient.subscribe(`/topic/retro.${roomId}`, function(message) {
      const data = JSON.parse(message.body);
      
      // Check if this error is for the current user (only redirect if rejectedUserId matches)
      const isRejected = data.rejectedUserId === retroUserId;
      
      if (data.error) {
        if (isRejected) {
          // Only redirect if this error is for the current user
          if (data.roomFull) {
            retroShowToast(data.error, 'error', 5000);
            setTimeout(() => {
              window.location.href = `${window.location.origin}/index.html`;
            }, 3000);
          } else {
            retroShowToast(data.error, 'error');
          }
        } else {
          // Error for another user, but include state update if provided
          if (data.state) {
            retroCurrentState = data.state;
            retroRenderBoard(retroCurrentState);
          }
          if (data.names) {
            const count = Object.keys(data.names).length;
            const countEl = document.getElementById('retro-user-count');
            if (countEl) countEl.textContent = `${count}/10`;
            
            // Display team member names
            const namesList = document.getElementById('retro-team-members-list');
            if (namesList) {
              const names = Object.values(data.names);
              if (names.length > 0) {
                namesList.innerHTML = '<div class="team-members-title">Joined:</div><div class="team-members-names">' + 
                  names.map(name => `<span class="team-member-badge">${retroEscapeHtml(name)}</span>`).join('') + 
                  '</div>';
                namesList.style.display = 'flex';
              } else {
                namesList.style.display = 'none';
              }
            }
          }
        }
        return;
      }
      if (data.names) {
        const count = Object.keys(data.names).length;
        const countEl = document.getElementById('retro-user-count');
        if (countEl) countEl.textContent = `${count}/10`;
        
        // Display team member names
        const namesList = document.getElementById('retro-team-members-list');
        if (namesList) {
          const names = Object.values(data.names);
          if (names.length > 0) {
            namesList.innerHTML = '<div class="team-members-title">Joined:</div><div class="team-members-names">' + 
              names.map(name => `<span class="team-member-badge">${retroEscapeHtml(name)}</span>`).join('') + 
              '</div>';
            namesList.style.display = 'flex';
          } else {
            namesList.style.display = 'none';
          }
        }
      }
      // Handle error feedback
      if (data.error) {
        if (data.error.includes('Vote limit')) {
          retroShowToast('You\'ve used all 5 votes!', 'warning', 3000);
        } else if (data.error.includes('card author')) {
          retroShowToast('Only the author or host can delete this note.', 'warning', 3000);
        } else if (data.error.includes('host can start')) {
          retroShowToast('Only the host can start the timer.', 'warning', 3000);
        }
      }
      // Update remaining votes display
      if (typeof data.remainingVotes !== 'undefined') {
        retroUpdateRemainingVotes(data.remainingVotes);
      }
      // Handle timer
      if (data.timerStartedAt) {
        retroStartTimerDisplay(data.timerStartedAt, data.timerDurationMs || 600000);
      }
      if (data.state) {
        retroCurrentState = data.state;
        retroRenderBoard(retroCurrentState);
      }
    });

    retroStompClient.send(`/app/retro.join.${roomId}`, {}, JSON.stringify({ userId: retroUserId, userName: retroUserName }));
    sessionStorage.setItem('retroRoomId', roomId);
  }, function(error) {
    console.error('Retro connection error:', error);
    retroShowConnectionBanner(true);
    retroShowToast('Connection lost - reconnecting...', 'warning');
    setTimeout(() => retroConnect(roomId), 5000);
  });

  // Enhanced disconnect handling
  const retroDisconnectHandler = () => {
    if (retroStompClient && retroStompClient.connected && retroUserId) {
      try {
        retroStompClient.send(`/app/retro.leave.${roomId}`, {}, JSON.stringify({ userId: retroUserId }));
      } catch (e) {
        console.error('Error sending leave message:', e);
      }
      retroStompClient.disconnect();
    }
    sessionStorage.removeItem('retroRoomId');
  };

  window.addEventListener('beforeunload', retroDisconnectHandler);
  window.addEventListener('pagehide', retroDisconnectHandler);
  window.addEventListener('unload', retroDisconnectHandler);
}

function retroRenderBoard(state) {
  let totalCards = 0;
  let totalVotes = 0;
  
  ['wentWell', 'toImprove', 'actionItems'].forEach(column => {
    const container = document.getElementById(column);
    container.innerHTML = '';
    const cards = state[column] || [];
    const filtered = cards.filter(c => {
      if (!retroSearchQuery) return true;
      const q = retroSearchQuery.toLowerCase();
      return (c.text || '').toLowerCase().includes(q) || (c.authorName || '').toLowerCase().includes(q);
    });
    const toRender = retroSortEnabled ? [...filtered].sort((a, b) => (b.votes || 0) - (a.votes || 0)) : filtered;

    totalCards += cards.length;
    totalVotes += cards.reduce((sum, card) => sum + (card.votes || 0), 0);

    toRender.forEach((card, index) => {
      const el = document.createElement('div');
      el.className = 'retro-card-enhanced';
      if (index === 0 && toRender.length > 0) {
        el.classList.add('new-card');
      }
      
      const createdDate = card.timestamp ? new Date(card.timestamp).toLocaleDateString() : 'Today';
      
      const isAuthor = card.authorId === retroUserId;
      const hostNameEl = document.getElementById('retro-host-name');
      const currentUserName = document.getElementById('retro-user-name')?.textContent;
      const isHost = hostNameEl && currentUserName && hostNameEl.textContent === currentUserName;
      const canDelete = isAuthor || isHost;

      el.innerHTML = `
        <div class="card-content">
          <div class="card-text">
            <div class="card-main-text">${retroEscapeHtml(card.text)}</div>
            <div class="card-meta">
              <div class="card-author">
                <span class="card-author-icon ic" data-ic="user" aria-hidden="true"></span>
                <span>${retroEscapeHtml(card.authorName)}</span>
              </div>
              <span class="card-timestamp">${createdDate}</span>
            </div>
          </div>
          <div class="card-actions">
            <button class="card-vote-btn retro-upvote" data-column="${column}" data-id="${card.id}" title="Vote for this item" aria-label="Vote (${card.votes || 0} votes)">
              <span class="vote-icon ic" data-ic="thumbs-up" aria-hidden="true"></span>
              <span class="vote-count">${card.votes || 0}</span>
            </button>
            ${canDelete ? `<button class="card-delete-btn retro-delete" data-column="${column}" data-id="${card.id}" title="Delete this item" aria-label="Delete note">
              <span class="ic" data-ic="trash" aria-hidden="true"></span>
            </button>` : ''}
          </div>
        </div>`;
      container.appendChild(el);
      if (typeof window.hydrateIcons === 'function') window.hydrateIcons(el);
    });

    if (toRender.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'retro-empty-enhanced';
      
      const emptyIcons = {
        wentWell: 'check-circle',
        toImprove: 'lightbulb',
        actionItems: 'rocket'
      };
      
      const emptyTitles = {
        wentWell: 'No successes yet',
        toImprove: 'No challenges identified',
        actionItems: 'No actions planned'
      };
      
      const emptySubtitles = {
        wentWell: 'Share what went well this sprint',
        toImprove: 'Identify areas for improvement',
        actionItems: 'Plan actions for next sprint'
      };
      
      const msg = cards.length === 0 ?
        `<div class="empty-icon ic" data-ic="${emptyIcons[column]}" aria-hidden="true"></div>
         <div class="empty-title">${emptyTitles[column]}</div>
         <div class="empty-subtitle">${emptySubtitles[column]}</div>` :
        `<div class="empty-icon ic" data-ic="search" aria-hidden="true"></div>
         <div class="empty-title">No matching notes</div>
         <div class="empty-subtitle">Try adjusting your search</div>`;

      empty.innerHTML = msg;
      if (typeof window.hydrateIcons === 'function') window.hydrateIcons(empty);
      container.appendChild(empty);
    }
  });

  // Show/hide global "no results" message when search filters out all cards
  let noResults = document.getElementById('retro-no-results');
  if (retroSearchQuery && retroSearchQuery.trim()) {
    const allColumns = ['wentWell', 'toImprove', 'actionItems'];
    const totalVisible = allColumns.reduce((sum, col) => {
      const cards = (state[col] || []);
      const q = retroSearchQuery.toLowerCase();
      return sum + cards.filter(c => (c.text || '').toLowerCase().includes(q) || (c.authorName || '').toLowerCase().includes(q)).length;
    }, 0);
    if (totalVisible === 0) {
      if (!noResults) {
        noResults = document.createElement('div');
        noResults.id = 'retro-no-results';
        noResults.style.cssText = 'text-align:center;color:var(--text-secondary, #6B6F8E);padding:2rem;font-size:0.9rem;';
        noResults.textContent = 'No cards match your search.';
        document.querySelector('.retro-board-grid')?.appendChild(noResults);
      }
    } else if (noResults) {
      noResults.remove();
    }
  } else if (noResults) {
    noResults.remove();
  }

  // Update statistics
  retroUpdateStatistics(totalCards, totalVotes);

  // Add event listeners with enhanced feedback
  document.querySelectorAll('.retro-upvote').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      const column = btn.getAttribute('data-column');
      const cardId = btn.getAttribute('data-id');
      const roomId = document.getElementById('retro-room-code').textContent;
      
      // Add visual feedback
      btn.classList.add('voted');
      setTimeout(() => btn.classList.remove('voted'), 500);
      
      if (!retroStompClient || !retroStompClient.connected) {
        retroShowToast('Not connected. Please refresh the page.', 'error', 3000);
        return;
      }
      retroStompClient.send(`/app/retro.card.upvote.${roomId}`, {}, JSON.stringify({ column, cardId, userId: retroUserId }));
    });
  });
  
  document.querySelectorAll('.retro-delete').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      const column = btn.getAttribute('data-column');
      const cardId = btn.getAttribute('data-id');
      const roomId = document.getElementById('retro-room-code').textContent;
      showConfirmDialog('This note will be permanently removed from the board.', 'Delete Note', () => {
        if (!retroStompClient || !retroStompClient.connected) {
          retroShowToast('Not connected. Please refresh the page.', 'error', 3000);
          return;
        }
        retroStompClient.send(`/app/retro.card.delete.${roomId}`, {}, JSON.stringify({ column, cardId, userId: retroUserId }));
        retroShowToast('Note deleted 🗑️', 'info', 2000);
      });
    });
  });
}

function retroEscapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function retroUpdateStatistics(totalCards, totalVotes) {
  const totalCardsEl = document.getElementById('total-cards');
  const totalVotesEl = document.getElementById('total-votes');
  const clearAllBtn = document.getElementById('clear-all-cards');
  
  if (totalCardsEl) totalCardsEl.textContent = totalCards;
  if (totalVotesEl) totalVotesEl.textContent = totalVotes;
  
  // Show/hide clear all button based on whether there are cards
  if (clearAllBtn) {
    clearAllBtn.style.display = totalCards > 0 ? 'flex' : 'none';
  }
}

// ─── Remaining Votes Display ────────────────────────────────
function retroUpdateRemainingVotes(remaining) {
  var el = document.getElementById('retro-remaining-votes');
  if (el) {
    el.textContent = remaining + '/5 votes left';
    el.classList.toggle('votes-low', remaining <= 1);
    el.classList.toggle('votes-empty', remaining === 0);
  }
}

// ─── Discussion Timer ───────────────────────────────────────
var retroTimerInterval = null;

function retroStartTimerDisplay(startedAt, durationMs) {
  if (retroTimerInterval) clearInterval(retroTimerInterval);

  var timerEl = document.getElementById('retro-timer-display');
  var timerBtn = document.getElementById('retro-timer-start-btn');
  if (!timerEl) return;

  timerEl.style.display = 'inline-flex';
  if (timerBtn) timerBtn.style.display = 'none';

  function tick() {
    var elapsed = Date.now() - startedAt;
    var remaining = Math.max(0, durationMs - elapsed);
    var mins = Math.floor(remaining / 60000);
    var secs = Math.floor((remaining % 60000) / 1000);
    timerEl.textContent = (mins < 10 ? '0' : '') + mins + ':' + (secs < 10 ? '0' : '') + secs;

    if (remaining <= 60000) {
      timerEl.classList.add('timer-warning');
    }
    if (remaining <= 0) {
      clearInterval(retroTimerInterval);
      timerEl.textContent = "Time's up!";
      timerEl.classList.add('timer-ended');
      // Restore start button for host to restart
      if (timerBtn) timerBtn.style.display = 'inline-flex';
    }
  }

  tick();
  retroTimerInterval = setInterval(tick, 1000);
}

function retroHandleTimerStart() {
  var roomId = document.getElementById('retro-room-code').textContent;
  if (!retroStompClient || !retroStompClient.connected) {
    retroShowToast('Not connected.', 'error');
    return;
  }
  retroStompClient.send('/app/retro.timer.start.' + roomId, {}, JSON.stringify({ userId: retroUserId }));
}

// Enhanced add card functionality with better UX
function retroSetupAddCardListeners() {
  document.querySelectorAll('.add-card-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      retroAddCard(btn);
    });
  });

  ['wentWell', 'toImprove', 'actionItems'].forEach(column => {
    const input = document.getElementById(`${column}-input`);
    if (input) {
      input.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          const btn = input.parentElement.querySelector('.add-card-btn');
          retroAddCard(btn);
        }
      });
      
      // Auto-resize textarea as user types
      input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
      });
    }
  });
}

function retroAddCard(btn) {
  const column = btn.getAttribute('data-column');
  const input = document.getElementById(`${column}-input`);
  const text = (input.value || '').trim();
  
  if (!text) {
    input.focus();
    input.classList.add('error-shake');
    setTimeout(() => input.classList.remove('error-shake'), 500);
    return;
  }
  
  const roomId = document.getElementById('retro-room-code').textContent;
  
  if (!retroStompClient || !retroStompClient.connected) {
    retroShowToast('Not connected. Please refresh the page.', 'error', 3000);
    return;
  }
  
  // Add loading state
  btn.disabled = true;
  const originalContent = btn.innerHTML;
  btn.innerHTML = '<span class="add-icon">⏳</span><span class="add-text">Adding...</span>';
  
  retroStompClient.send(`/app/retro.card.add.${roomId}`, {}, JSON.stringify({ 
    column, 
    text, 
    userId: retroUserId, 
    userName: retroUserName,
    timestamp: Date.now()
  }));
  
  input.value = '';
  input.style.height = 'auto';
  
  // Reset button state after a brief delay
  setTimeout(() => {
    btn.disabled = false;
    btn.innerHTML = originalContent;
  }, 500);
  
  retroShowToast('Note added! ✨', 'success', 2000);
}

// Enhanced sort functionality
const retroSortButton = document.getElementById('retro-sort-votes');
if (retroSortButton) {
  retroSortButton.addEventListener('click', () => {
    retroSortEnabled = !retroSortEnabled;
    retroSortButton.setAttribute('aria-pressed', String(retroSortEnabled));
    
    const btnText = retroSortButton.querySelector('.btn-text');
    const btnIcon = retroSortButton.querySelector('.btn-icon');
    
    if (btnText) {
      btnText.textContent = retroSortEnabled ? 'Sorted by Votes' : 'Sort by Votes';
    }
    if (btnIcon && typeof window.setIcon === 'function') {
      window.setIcon(btnIcon, 'bar-chart');
    }
    
    if (retroCurrentState) retroRenderBoard(retroCurrentState);
    retroShowToast(retroSortEnabled ? 'Sorting by votes 📊' : 'Default order 📝', 'info', 1500);
  });
}

// Enhanced search with debouncing
const retroSearchInput = document.getElementById('retro-search');
if (retroSearchInput) {
  let searchTimeout;
  
  retroSearchInput.addEventListener('input', (e) => {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      retroSearchQuery = (e.target.value || '').trim();
      if (retroCurrentState) retroRenderBoard(retroCurrentState);
      
      if (retroSearchQuery && retroSearchQuery.length > 0) {
        retroShowToast(`Searching for "${retroSearchQuery}" 🔍`, 'info', 1500);
      }
    }, 300); // Debounce search by 300ms
  });
  
  // Clear search on Escape
  retroSearchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      e.target.value = '';
      retroSearchQuery = '';
      if (retroCurrentState) retroRenderBoard(retroCurrentState);
      retroShowToast('Search cleared 🔍', 'info', 1500);
    }
  });
}

// Enhanced add card animation CSS
function retroAddAnimationStyles() {
  if (!document.getElementById('retro-animation-styles')) {
    const style = document.createElement('style');
    style.id = 'retro-animation-styles';
    style.textContent = `
      @keyframes errorShake {
        0%, 100% { transform: translateX(0); }
        25% { transform: translateX(-5px); }
        75% { transform: translateX(5px); }
      }
      
      .error-shake {
        animation: errorShake 0.3s ease-in-out;
        border-color: var(--color-danger) !important;
        box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.1) !important;
      }
      
      .modal-name-input.error-shake {
        border-color: var(--color-danger) !important;
        box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.1) !important;
      }
      
      .add-card-btn:disabled {
        opacity: 0.7;
        cursor: not-allowed;
        transform: none !important;
      }
    `;
    document.head.appendChild(style);
  }
}

window.onload = async function() {
  // Add animation styles
  retroAddAnimationStyles();
  
  const roomId = retroGetRoomIdFromUrl();
  if (roomId) {
    retroUserId = retroGenerateUserId();
    
    // Check if we have a stored username, otherwise show beautiful modal
    let storedUserName = sessionStorage.getItem('retroUserName');
    if (storedUserName && storedUserName.trim() && storedUserName.length <= 20) {
      retroUserName = storedUserName;
    } else {
      try {
        retroUserName = await retroPromptForUserName();
        if (!retroUserName) {
          // User cancelled, redirect to home
          retroShowToast('Joining cancelled. Redirecting to home...', 'info', 2000);
          setTimeout(() => {
            window.location.href = `${window.location.origin}/index.html`;
          }, 2000);
          return;
        }
        // Store the username for future use
        sessionStorage.setItem('retroUserName', retroUserName);
      } catch (error) {
        console.error('Error getting username:', error);
        retroShowToast('Error joining session. Please try again.', 'error');
        setTimeout(() => {
          window.location.href = `${window.location.origin}/index.html`;
        }, 3000);
        return;
      }
    }
    
    const roomSection = document.getElementById('retro-room-section');
    if (roomSection) roomSection.style.display = 'none';
    
    // Hide the rules section when viewing a board
    const rulesSection = document.getElementById('retro-rules-section');
    if (rulesSection) rulesSection.style.display = 'none';
    
    document.getElementById('retro-room-code').textContent = roomId;
    document.getElementById('retro-user-name').textContent = retroUserName;
    document.getElementById('retro-room-info').style.display = 'block';
    retroUpdateTeamNameUI();
    document.getElementById('retro-board').style.display = 'block';
    
    // Setup enhanced event listeners for the board
    retroSetupAddCardListeners();
    
    // Show welcome toast
    retroShowToast(`Welcome to the retrospective, ${retroUserName}! 🎉`, 'success');
    
    retroConnect(roomId);
  } else {
    // Add input event listeners for better UX
    const nameInput = document.getElementById('retro-name-input');
    const roomCodeInput = document.getElementById('retro-room-code-input');
    
    if (nameInput) {
      nameInput.addEventListener('keydown', e => {
        if (e.key === 'Enter') {
          e.preventDefault();
          document.getElementById('retro-create-room-btn').click();
        }
      });
      
      // Clear name error when user starts typing
      nameInput.addEventListener('input', () => {
        retroShowNameError(false);
      });
    }
    
    if (roomCodeInput) {
      roomCodeInput.addEventListener('keydown', e => {
        if (e.key === 'Enter') {
          e.preventDefault();
          document.getElementById('retro-join-room-btn').click();
        }
      });
      
      // Clear join error when user starts typing
      roomCodeInput.addEventListener('input', () => {
        retroShowJoinError(false);
      });
    }
  }
};


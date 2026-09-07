/**
 * Copyright (c) 2026 rebcoder. MIT licensed — see LICENSE.
 * Share Modal functionality for room sharing
 */

(function() {
  'use strict';

  // Create share modal HTML
  function createShareModal() {
    const modal = document.createElement('div');
    modal.id = 'share-modal-backdrop';
    modal.className = 'share-modal-backdrop';
    modal.style.display = 'none';
    modal.innerHTML = `
      <div class="share-modal">
        <div class="share-modal-header">
          <h3 class="share-modal-title">Share Room</h3>
          <button class="share-modal-close" aria-label="Close share modal"><span class="ic" data-ic="x" aria-hidden="true"></span></button>
        </div>
        <div class="share-modal-content">
          <div class="share-url-container">
            <input type="text" id="share-url-input" class="share-url-input" readonly />
            <button id="copy-url-btn" class="share-action-btn copy-btn" title="Copy link">
              <span class="btn-icon ic" data-ic="clipboard-list" aria-hidden="true"></span>
              <span class="btn-text">Copy Link</span>
            </button>
          </div>
          <div class="share-actions">
            <button id="native-share-btn" class="share-action-btn native-share-btn" title="Share">
              <span class="btn-icon ic" data-ic="smartphone" aria-hidden="true"></span>
              <span class="btn-text">Share</span>
            </button>
            <button id="whatsapp-share-btn" class="share-action-btn whatsapp-btn" title="Share on WhatsApp">
              <span class="btn-icon ic" data-ic="message-circle" aria-hidden="true"></span>
              <span class="btn-text">WhatsApp</span>
            </button>
            <button id="email-share-btn" class="share-action-btn email-btn" title="Share via Email">
              <span class="btn-icon ic" data-ic="mail" aria-hidden="true"></span>
              <span class="btn-text">Email</span>
            </button>
          </div>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
    if (typeof window.hydrateIcons === 'function') window.hydrateIcons(modal);
    return modal;
  }

  // Get or create modal
  function getShareModal() {
    let modal = document.getElementById('share-modal-backdrop');
    if (!modal) {
      modal = createShareModal();
      attachModalEvents(modal);
    }
    return modal;
  }

  // Attach event listeners to modal
  function attachModalEvents(modal) {
    const closeBtn = modal.querySelector('.share-modal-close');
    const backdrop = modal;
    const copyBtn = modal.querySelector('#copy-url-btn');
    const nativeShareBtn = modal.querySelector('#native-share-btn');
    const whatsappBtn = modal.querySelector('#whatsapp-share-btn');
    const emailBtn = modal.querySelector('#email-share-btn');

    // Close modal
    closeBtn.addEventListener('click', () => closeShareModal());
    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) {
        closeShareModal();
      }
    });

    // Copy link
    copyBtn.addEventListener('click', () => copyRoomUrl());

    // Native share
    nativeShareBtn.addEventListener('click', () => nativeShareRoom());

    // WhatsApp share
    whatsappBtn.addEventListener('click', () => shareViaWhatsApp());

    // Email share
    emailBtn.addEventListener('click', () => shareViaEmail());

    // Hide native share button if not supported
    if (!navigator.share) {
      nativeShareBtn.style.display = 'none';
    }
  }

  // Generate share URL (query params so /join is served by static server; path /join/type/id would 404)
  function generateShareUrl(roomId, roomType) {
    const baseUrl = window.location.origin;
    return `${baseUrl}/join?type=${encodeURIComponent(roomType)}&room=${encodeURIComponent(roomId)}`;
  }

  // Get room info from current page
  function getRoomInfo() {
    // Try to get from URL params (poker.html?room=ABCD12)
    const urlParams = new URLSearchParams(window.location.search);
    const roomId = urlParams.get('room');
    
    // Determine room type from current page (supports both /retro and /retro.html URLs)
    let roomType = 'poker';
    const path = window.location.pathname;
    if (path.includes('retro')) {
      roomType = 'retro';
    } else if (path.includes('mood')) {
      roomType = 'mood';
    }

    return { roomId, roomType };
  }

  // Open share modal
  function openShareModal() {
    const { roomId, roomType } = getRoomInfo();
    
    if (!roomId) {
      showToast('Room ID not found', 'error');
      return;
    }

    const modal = getShareModal();
    const urlInput = modal.querySelector('#share-url-input');
    const shareUrl = generateShareUrl(roomId, roomType);
    
    urlInput.value = shareUrl;
    modal.style.display = 'flex';
    
    // Focus on URL input for easy selection
    setTimeout(() => {
      urlInput.select();
      urlInput.setSelectionRange(0, shareUrl.length);
    }, 100);
  }

  // Close share modal
  function closeShareModal() {
    const modal = document.getElementById('share-modal-backdrop');
    if (modal) {
      modal.style.display = 'none';
    }
  }

  // Copy room URL to clipboard
  async function copyRoomUrl() {
    const modal = document.getElementById('share-modal-backdrop');
    const urlInput = modal.querySelector('#share-url-input');
    const url = urlInput.value;

    try {
      await navigator.clipboard.writeText(url);
      showToast('Link copied to clipboard!', 'success');
      
      // Visual feedback
      const copyBtn = modal.querySelector('#copy-url-btn');
      const originalText = copyBtn.innerHTML;
      copyBtn.innerHTML = '<span class="btn-icon ic" data-ic="check-circle" aria-hidden="true"></span><span class="btn-text">Copied!</span>';
      if (typeof window.hydrateIcons === 'function') window.hydrateIcons(copyBtn);
      copyBtn.disabled = true;
      
      setTimeout(() => {
        copyBtn.innerHTML = originalText;
        copyBtn.disabled = false;
      }, 2000);
    } catch (err) {
      // Fallback for older browsers
      urlInput.select();
      urlInput.setSelectionRange(0, url.length);
      try {
        document.execCommand('copy');
        showToast('Link copied to clipboard!', 'success');
      } catch (fallbackErr) {
        showToast('Failed to copy link. Please copy manually.', 'error');
      }
    }
  }

  // Native share
  async function nativeShareRoom() {
    const { roomId, roomType } = getRoomInfo();
    const shareUrl = generateShareUrl(roomId, roomType);
    
    const roomTypeNames = {
      'poker': 'Scrum Poker Room',
      'retro': 'Sprint Retrospective Board'
    };

    const shareData = {
      title: `Join ${roomTypeNames[roomType] || 'Room'}`,
      text: `Join our ${roomTypeNames[roomType] || 'room'} session`,
      url: shareUrl
    };

    try {
      if (navigator.share) {
        await navigator.share(shareData);
        showToast('Shared successfully!', 'success');
      } else {
        // Fallback to copy
        copyRoomUrl();
      }
    } catch (err) {
      if (err.name !== 'AbortError') {
        showToast('Failed to share', 'error');
      }
    }
  }

  // Share via WhatsApp
  function shareViaWhatsApp() {
    const { roomId, roomType } = getRoomInfo();
    const shareUrl = generateShareUrl(roomId, roomType);
    
    const roomTypeNames = {
      'poker': 'Scrum Poker Room',
      'retro': 'Sprint Retrospective Board'
    };

    const text = `Join our ${roomTypeNames[roomType] || 'room'}: ${shareUrl}`;
    const encodedText = encodeURIComponent(text);
    const whatsappUrl = `https://wa.me/?text=${encodedText}`;
    
    window.open(whatsappUrl, '_blank');
    showToast('Opening WhatsApp...', 'info');
  }

  // Share via Email
  function shareViaEmail() {
    const { roomId, roomType } = getRoomInfo();
    const shareUrl = generateShareUrl(roomId, roomType);
    
    const roomTypeNames = {
      'poker': 'Scrum Poker Room',
      'retro': 'Sprint Retrospective Board'
    };

    const subject = encodeURIComponent(`Join ${roomTypeNames[roomType] || 'Room'}`);
    const body = encodeURIComponent(`Join our ${roomTypeNames[roomType] || 'room'} session:\n\n${shareUrl}`);
    const mailtoUrl = `mailto:?subject=${subject}&body=${body}`;
    
    window.location.href = mailtoUrl;
  }

  // Show toast notification
  function showToast(message, type = 'info') {
    // Try to use existing toast function if available
    if (typeof window.showToast === 'function') {
      window.showToast(message, type);
      return;
    }

    // Fallback toast implementation
    const toast = document.createElement('div');
    toast.className = `toast-message toast-${type}`;
    toast.textContent = message;
    toast.style.cssText = `
      position: fixed;
      bottom: 20px;
      left: 50%;
      transform: translateX(-50%);
      background: #333;
      color: #fff;
      padding: 12px 24px;
      border-radius: 8px;
      z-index: 10001;
      animation: fadeInOut 2.5s ease-in-out;
    `;
    document.body.appendChild(toast);
    
    setTimeout(() => {
      toast.remove();
    }, 2500);
  }

  // Export functions
  window.openShareModal = openShareModal;
  window.closeShareModal = closeShareModal;
})();


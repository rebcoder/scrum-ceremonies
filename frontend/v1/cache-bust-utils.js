/**
 * Cache-busting and WebSocket error detection utilities
 * 
 * This script provides:
 * 1. WebSocket error detection for incompatible server responses
 * 2. Automatic page reload on critical errors
 * 3. Version compatibility checking
 */

(function() {
    'use strict';
    
    // Track WebSocket connection errors
    let wsErrorCount = 0;
    const MAX_WS_ERRORS = 3;
    const WS_ERROR_RESET_TIME = 30000; // 30 seconds
    
    // Track incompatible server responses
    let incompatibleResponseDetected = false;
    
    /**
     * Detects if a WebSocket error indicates an incompatible server version
     * @param {string} error - Error message or response
     * @returns {boolean}
     */
    function isIncompatibleError(error) {
        if (!error) return false;
        
        const errorStr = typeof error === 'string' ? error : JSON.stringify(error);
        const lowerError = errorStr.toLowerCase();
        
        // Check for common incompatible response patterns
        const incompatiblePatterns = [
            'version mismatch',
            'incompatible',
            'protocol error',
            'desync',
            'invalid message format',
            'unexpected response',
            'room not found', // Could indicate server restart
            'connection refused'
        ];
        
        return incompatiblePatterns.some(pattern => lowerError.includes(pattern));
    }
    
    /**
     * Detects room-related errors that might indicate server/client version mismatch
     * @param {Object} response - Server response object
     * @returns {boolean}
     */
    function isRoomError(response) {
        if (!response) return false;
        
        // Check for room errors that might indicate version issues
        if (response.error) {
            const errorStr = String(response.error).toLowerCase();
            return errorStr.includes('room') && (
                errorStr.includes('not found') ||
                errorStr.includes('invalid') ||
                errorStr.includes('error')
            );
        }
        
        return false;
    }
    
    /**
     * Forces a full page reload with cache bypass
     */
    function forceReload() {
        console.warn('Forcing page reload due to incompatible server response or WebSocket error');
        
        // Clear session storage to force fresh start
        try {
            sessionStorage.clear();
        } catch (e) {
            console.warn('Could not clear sessionStorage:', e);
        }
        
        // Force reload with cache bypass
        if (window.location && window.location.reload) {
            window.location.reload(true);
        } else {
            window.location.href = window.location.href.split('?')[0] + '?nocache=' + Date.now();
        }
    }
    
    /**
     * Handles WebSocket connection errors
     * @param {Error|string} error - Error object or message
     * @param {Object} context - Additional context (roomId, etc.)
     */
    function handleWebSocketError(error, context) {
        wsErrorCount++;
        
        console.error('WebSocket error:', error, context);
        
        // Check if error indicates incompatibility
        if (isIncompatibleError(error)) {
            incompatibleResponseDetected = true;
            console.warn('Incompatible server response detected, forcing reload');
            setTimeout(forceReload, 1000);
            return;
        }
        
        // If too many errors in short time, force reload
        if (wsErrorCount >= MAX_WS_ERRORS) {
            console.warn('Too many WebSocket errors, forcing reload');
            setTimeout(forceReload, 2000);
            return;
        }
        
        // Reset error count after timeout
        setTimeout(() => {
            wsErrorCount = Math.max(0, wsErrorCount - 1);
        }, WS_ERROR_RESET_TIME);
    }
    
    /**
     * Handles server response and checks for compatibility issues
     * @param {Object} response - Server response
     * @param {Object} context - Additional context
     */
    function handleServerResponse(response, context) {
        if (!response) return;
        
        // Check for room errors
        if (isRoomError(response)) {
            console.warn('Room error detected:', response);
            // Don't immediately reload on room errors, but track them
            if (wsErrorCount >= 2) {
                incompatibleResponseDetected = true;
                setTimeout(forceReload, 3000);
            }
            return;
        }
        
        // Check for unexpected response structure (might indicate version mismatch)
        if (response.error && typeof response.error === 'string') {
            if (isIncompatibleError(response.error)) {
                incompatibleResponseDetected = true;
                setTimeout(forceReload, 1000);
                return;
            }
        }
    }
    
    /**
     * Monitors WebSocket connection state
     * @param {Object} stompClient - STOMP client instance
     * @param {string} roomId - Room ID
     */
    function monitorWebSocketConnection(stompClient, roomId) {
        if (!stompClient) return;
        
        let lastConnectedState = stompClient.connected;
        let disconnectCount = 0;
        const MAX_DISCONNECTS = 5;
        
        const checkInterval = setInterval(() => {
            if (!stompClient) {
                clearInterval(checkInterval);
                return;
            }
            
            const currentState = stompClient.connected;
            
            // Track disconnections
            if (lastConnectedState && !currentState) {
                disconnectCount++;
                console.warn('WebSocket disconnected, count:', disconnectCount);
                
                if (disconnectCount >= MAX_DISCONNECTS) {
                    console.warn('Too many disconnections, forcing reload');
                    clearInterval(checkInterval);
                    setTimeout(forceReload, 2000);
                    return;
                }
            } else if (currentState) {
                disconnectCount = 0; // Reset on successful connection
            }
            
            lastConnectedState = currentState;
        }, 5000); // Check every 5 seconds
    }
    
    // Export functions to global scope
    window.cacheBustUtils = {
        handleWebSocketError: handleWebSocketError,
        handleServerResponse: handleServerResponse,
        monitorWebSocketConnection: monitorWebSocketConnection,
        forceReload: forceReload,
        isIncompatibleError: isIncompatibleError
    };
    
    // Auto-detect and handle common error patterns
    window.addEventListener('error', function(event) {
        if (event.message && isIncompatibleError(event.message)) {
            console.warn('Global error detected, checking for incompatibility');
            setTimeout(forceReload, 2000);
        }
    });
    
    console.log('Cache-busting and WebSocket error detection utilities loaded');
})();


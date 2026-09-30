-- ====================================================================
-- TeknoyCart Migration: Secure Chat Auto-Reply RPC
-- Description:
--   Allows authenticated buyers/sellers to trigger automated assistant
--   replies in an active chat without violating RLS policies.
--   Executes with SECURITY DEFINER to insert on behalf of the conversation
--   counterpart, strictly validating that:
--     1. The caller is authenticated.
--     2. The caller is an active participant (buyer or seller) in the chat.
--     3. The auto-reply sender is guaranteed to be the other participant.
--     4. Spam & loop protection: identical messages within 5s are suppressed.
-- ====================================================================

CREATE OR REPLACE FUNCTION public.send_automated_chat_reply(
    p_chat_id UUID,
    p_content TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_caller_id UUID;
    v_buyer_id UUID;
    v_seller_id UUID;
    v_reply_sender_id UUID;
    v_new_message_id UUID;
    v_sent_at TIMESTAMP WITH TIME ZONE;
BEGIN
    -- 1. Authentication check
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- 2. Verify chat existence and participant authorization
    SELECT buyer_id, seller_id INTO v_buyer_id, v_seller_id
    FROM public.chats
    WHERE chat_id = p_chat_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Chat room not found with id: %', p_chat_id;
    END IF;

    IF v_caller_id != v_buyer_id AND v_caller_id != v_seller_id THEN
        RAISE EXCEPTION 'Unauthorized: Caller is not a participant in chat %', p_chat_id;
    END IF;

    -- 3. Determine the target sender (the counterpart in the room)
    IF v_caller_id = v_buyer_id THEN
        v_reply_sender_id := v_seller_id;
    ELSE
        v_reply_sender_id := v_buyer_id;
    END IF;

    -- 4. Anti-spam / Duplicate loop suppression
    IF EXISTS (
        SELECT 1 FROM public.messages
        WHERE chat_id = p_chat_id
          AND sender_id = v_reply_sender_id
          AND content = p_content
          AND sent_at > (NOW() - INTERVAL '5 seconds')
    ) THEN
        RETURN jsonb_build_object(
            'success', false,
            'reason', 'duplicate_suppressed'
        );
    END IF;

    -- 5. Insert automated reply safely
    INSERT INTO public.messages (
        chat_id,
        sender_id,
        content,
        image_url,
        is_read,
        sent_at
    ) VALUES (
        p_chat_id,
        v_reply_sender_id,
        p_content,
        NULL,
        FALSE,
        NOW()
    )
    RETURNING message_id, sent_at
    INTO v_new_message_id, v_sent_at;

    RETURN jsonb_build_object(
        'success', true,
        'message_id', v_new_message_id,
        'sender_id', v_reply_sender_id,
        'sent_at', v_sent_at
    );
END;
$$;

-- Grant execution permissions to authenticated clients and backend service role
GRANT EXECUTE ON FUNCTION public.send_automated_chat_reply(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.send_automated_chat_reply(UUID, TEXT) TO service_role;

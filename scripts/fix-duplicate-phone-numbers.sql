-- Script to fix duplicate phone numbers in the database
-- Run this script before deploying the code changes that add unique constraint to phone_number

-- ===================================
-- STEP 1: Identify duplicate phone numbers
-- ===================================
-- This query shows all phone numbers that have duplicates and how many times they appear
SELECT phone_number, COUNT(*) as count
FROM users
WHERE phone_number IS NOT NULL AND phone_number != ''
GROUP BY phone_number
HAVING COUNT(*) > 1
ORDER BY count DESC;

-- ===================================
-- STEP 2: View detailed information about duplicates
-- ===================================
-- This query shows all details of users with duplicate phone numbers
-- Review this carefully to decide which users to keep
SELECT u.*
FROM users u
WHERE u.phone_number IN (
    SELECT phone_number
    FROM users
    WHERE phone_number IS NOT NULL AND phone_number != ''
    GROUP BY phone_number
    HAVING COUNT(*) > 1
)
ORDER BY u.phone_number, u.created_at DESC;

-- ===================================
-- STEP 3: Automatic cleanup (CAREFUL!)
-- ===================================
-- This approach keeps the OLDEST user for each phone number
-- and marks duplicates with a modified phone number
-- 
-- IMPORTANT: Review the results from STEP 2 before running this!
-- Backup your database first!

-- Option A: Add suffix to duplicate phone numbers (safer - doesn't delete)
-- This keeps all users but makes phone numbers unique by adding a suffix
UPDATE users u1
SET phone_number = phone_number || '_DUP_' || u1.id
WHERE u1.id NOT IN (
    SELECT MIN(u2.id)
    FROM users u2
    WHERE u2.phone_number = u1.phone_number
    AND u2.phone_number IS NOT NULL 
    AND u2.phone_number != ''
    GROUP BY u2.phone_number
)
AND u1.phone_number IN (
    SELECT phone_number
    FROM users
    WHERE phone_number IS NOT NULL AND phone_number != ''
    GROUP BY phone_number
    HAVING COUNT(*) > 1
)
AND u1.phone_number NOT LIKE '%_DUP_%';

-- ===================================
-- STEP 4: Verify the cleanup
-- ===================================
-- After running the cleanup, verify there are no more duplicates
SELECT phone_number, COUNT(*) as count
FROM users
WHERE phone_number IS NOT NULL AND phone_number != ''
GROUP BY phone_number
HAVING COUNT(*) > 1;

-- This should return no rows if the cleanup was successful

-- ===================================
-- STEP 5: (Optional) Clean up users with _DUP_ suffix
-- ===================================
-- If you want to manually review and delete users with _DUP_ suffix:
SELECT *
FROM users
WHERE phone_number LIKE '%_DUP_%'
ORDER BY created_at;

-- To delete a specific duplicate user (replace <id> with actual ID):
-- DELETE FROM users WHERE id = <id>;

-- ===================================
-- NOTES:
-- ===================================
-- 1. Always backup your database before running cleanup scripts!
-- 2. Review STEP 2 results carefully to understand your data
-- 3. Option A (adding suffix) is safer than deletion
-- 4. After cleanup, the unique constraint will be enforced by the application
-- 5. If you have foreign key constraints, you may need to update related records first

-- ===================================
-- Alternative: Manual cleanup for specific phone number
-- ===================================
-- If you want to manually handle a specific phone number:
-- 
-- Example: Keep user with id=123 and mark others as duplicates
-- UPDATE users 
-- SET phone_number = phone_number || '_DUP_' || id 
-- WHERE phone_number = '+1234567890' AND id != 123;




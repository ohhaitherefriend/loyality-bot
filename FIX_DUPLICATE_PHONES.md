# Fix for Duplicate Phone Numbers Issue

## Problem Description

Your bot was experiencing this error:
```
org.springframework.dao.IncorrectResultSizeDataAccessException: query did not return a unique result: 9
```

This occurred because the database had 9 users with the same "phone number", but the code expected phone numbers to be unique.

## Root Cause

**The real issue:** Users were entering **purchase codes (6-digit codes) instead of phone numbers** during registration.

1. The bot didn't validate the format of phone numbers
2. When a user entered a purchase code (e.g., "ABC123" → "123" after normalization), it was saved as a "phone number"
3. Multiple users made the same mistake and entered the same code
4. The `phone_number` column didn't have a `UNIQUE` constraint, allowing duplicates
5. When the bot tried to find a user by "phone number", it found 9 users with the same code and crashed

**Why this happened:**
- No validation: 6-digit codes were accepted as phone numbers
- No unique constraint: Multiple users could have the same "phone number"
- Confusing UX: Users didn't understand they should use the button or enter a real phone

## What Was Fixed

### 1. Code Changes

#### a. User Entity (`src/main/java/com/plstk/loyaltybot/entity/User.java`)
- ✅ Added `unique = true` to the `phoneNumber` column
- This ensures the database will enforce uniqueness going forward

#### b. UserRepository (`src/main/java/com/plstk/loyaltybot/repository/UserRepository.java`)
- ✅ Added `findAllByPhoneNumber()` method to handle cases where duplicates exist

#### c. UserService (`src/main/java/com/plstk/loyaltybot/service/UserService.java`)
- ✅ Added error handling in `findByPhoneNumber()` to gracefully handle duplicates
- ✅ Added `findAllByPhoneNumber()` method for querying all users with a phone number

#### d. LoyaltyBot (`src/main/java/com/plstk/loyaltybot/bot/LoyaltyBot.java`)
- ✅ Improved phone number validation to exclude the current user
- ✅ Better error message when phone number is already taken

### 2. Database Cleanup Script

Created `scripts/fix-duplicate-phone-numbers.sql` to clean up existing duplicates.

## What You Need to Do

### Step 1: Backup Your Database (CRITICAL!)

Before making any changes, backup your database:

```bash
# If using H2 database
cp data/loyaltydb.mv.db data/loyaltydb.mv.db.backup
cp data/loyaltydb.trace.db data/loyaltydb.trace.db.backup

# Or use your database's backup tool
```

### Step 2: Clean Up Duplicate Phone Numbers

You have two options:

#### Option A: Automatic Cleanup (Recommended)

This will keep the oldest user for each phone number and add a `_DUP_<id>` suffix to duplicate phone numbers:

1. Connect to your database (see instructions below)
2. Run the queries in `scripts/fix-duplicate-phone-numbers.sql`
3. Start with STEP 1 and STEP 2 to see what duplicates exist
4. Run STEP 3 (Option A) to automatically mark duplicates
5. Run STEP 4 to verify cleanup was successful

#### Option B: Manual Cleanup

If you want more control:

1. Run STEP 1 and STEP 2 from the SQL script to see duplicates
2. Manually decide which users to keep
3. Update or delete specific users as needed

### Step 3: Deploy the Code Changes

Once the database is cleaned up:

```bash
# Rebuild the application
mvn clean package

# If using Docker:
docker-compose down
docker-compose up --build -d

# Or if using the run script:
./run.sh
```

### Step 4: Verify the Fix

1. Try to register a new user with a phone number
2. Try to use an existing phone number - you should get an error
3. Check the logs - the error should no longer appear

## How to Access Your Database

### For H2 Database (Development)

If you're using H2, you can access the web console:

1. Make sure the application is running
2. Go to: `http://localhost:8080/h2-console` (or your configured port)
3. Use the connection details from `application.yml`:
   - JDBC URL: `jdbc:h2:file:./data/loyaltydb`
   - Username: (check `application.yml`)
   - Password: (check `application.yml`)

### For Production Database

If you're using PostgreSQL or another database:

```bash
# PostgreSQL example
psql -h localhost -U your_username -d your_database

# Then run the SQL commands from the script
```

## Prevention

The code changes ensure this won't happen again:

1. ✅ Database constraint: `UNIQUE` on `phone_number` column
2. ✅ Application-level validation: checks for existing phone numbers
3. ✅ Error handling: gracefully handles edge cases
4. ✅ Better user messages: clearer error feedback

## Testing the Fix

After deployment, test these scenarios:

1. **New Registration**: 
   - Register a new user with a fresh phone number ✅ Should work

2. **Duplicate Prevention**: 
   - Try to register with an existing phone number ✅ Should show error

3. **Existing Users**: 
   - Existing users should continue to work normally ✅ Should work

4. **Phone Number Lookup**: 
   - Admin scanning a purchase code ✅ Should work without errors

## Rollback Plan

If something goes wrong:

1. Stop the application
2. Restore the database backup:
   ```bash
   cp data/loyaltydb.mv.db.backup data/loyaltydb.mv.db
   cp data/loyaltydb.trace.db.backup data/loyaltydb.trace.db
   ```
3. Revert the code changes:
   ```bash
   git checkout HEAD -- src/
   ```
4. Restart the application

## Questions?

If you encounter any issues:

1. Check the application logs: `docker-compose logs -f bot` (or wherever logs are)
2. Verify the database cleanup was successful (run STEP 4 from SQL script)
3. Make sure no old instances of the app are running
4. Check that the unique constraint is active: 
   ```sql
   SELECT * FROM INFORMATION_SCHEMA.CONSTRAINTS 
   WHERE TABLE_NAME = 'USERS' AND COLUMN_LIST = 'PHONE_NUMBER';
   ```

## Summary

- ✅ Fixed the code to enforce unique phone numbers
- ✅ Added graceful error handling for existing duplicates  
- ✅ Created cleanup script for database
- ⚠️ **YOU MUST RUN THE CLEANUP SCRIPT** before deploying the code changes
- ⚠️ **BACKUP YOUR DATABASE FIRST**

Once you complete Steps 1-4 above, the error should be completely resolved!


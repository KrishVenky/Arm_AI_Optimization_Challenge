"""Labeled eval set: call-transcript snippet -> expected label (SCAM/SAFE).
Not exhaustive -- enough to give the search a real accuracy signal instead of
none. 32 examples (doubled from the original 16, see chat/commit history):
with combined_score weighting accuracy at 0.6, one misclassified example
moves a candidate's score by 0.6/32 = 0.01875. At 16 examples that jump was
0.0375 -- comparable to or larger than most per-block signal measured from
real search results (roughly 0.03-0.18 across the 26 blocks), i.e. the eval
set was coarse enough to swamp the thing the search was trying to detect.
Still not a rigor claim -- expand further before citing accuracy numbers in
a write-up.
"""

EXAMPLES = [
    ("Hi this is your bank, we noticed suspicious activity, please read us the OTP code sent to your phone.", "SCAM"),
    ("This is the IRS. You owe back taxes and will be arrested today unless you pay now with gift cards.", "SCAM"),
    ("Congratulations, you won a free iPhone. Just confirm your card number to cover shipping.", "SCAM"),
    ("This is tech support, we detected a virus on your computer, please install this remote access app.", "SCAM"),
    ("Hi it's your grandson, I'm in jail and need bail money wired right now, don't tell mom.", "SCAM"),
    ("Your package could not be delivered, click the link and enter your payment details to reschedule.", "SCAM"),
    ("This is Amazon security, we see a fraudulent order, stay on the line and provide your password to cancel it.", "SCAM"),
    ("I'm calling about the extended warranty on your vehicle, act now before it expires and give me your VIN and card.", "SCAM"),
    ("This is the Social Security Administration, your number has been suspended due to suspicious activity, press 1 now or it will be permanently deactivated.", "SCAM"),
    ("Hi, this is your investment advisor's office, we need you to move your savings into a bitcoin wallet today before the opportunity closes.", "SCAM"),
    ("Hey love, it's me, I'm stuck at customs and need you to wire five hundred dollars right away so they'll release my luggage.", "SCAM"),
    ("We're collecting emergency donations for flood victims, can you read us your card number so we can process your gift right now?", "SCAM"),
    ("This is the electric company, your service will be disconnected within the hour unless you pay the past due balance with a prepaid card.", "SCAM"),
    ("You've been selected as a sweepstakes winner, just pay a small processing fee with your credit card to release the funds.", "SCAM"),
    ("This is the county clerk's office, you missed jury duty and there's a warrant out for your arrest unless you pay the fine over the phone right now.", "SCAM"),
    ("This is your bank's fraud department, we need you to transfer your funds to a new secure account we'll set up for you immediately.", "SCAM"),
    ("Hey, are we still on for lunch tomorrow at noon?", "SAFE"),
    ("This is the dentist's office confirming your cleaning appointment next Tuesday at 3pm.", "SAFE"),
    ("Can you pick up milk on your way home tonight?", "SAFE"),
    ("Hi, this is your doctor's office, your test results came back normal.", "SAFE"),
    ("Let's meet tomorrow afternoon at 3pm to discuss the project plan.", "SAFE"),
    ("This is the school calling, your kid forgot their lunch, just wanted you to know.", "SAFE"),
    ("Hey it's mom, just checking in, call me back when you get a chance.", "SAFE"),
    ("This is the plumber, I'll be there Thursday morning between nine and eleven.", "SAFE"),
    ("Hey, can we push our one-on-one to two o'clock instead of one, something came up.", "SAFE"),
    ("Hi, it's your neighbor, could I borrow your ladder this weekend for a few hours?", "SAFE"),
    ("Are you free Friday night, a few of us are getting dinner downtown.", "SAFE"),
    ("This is your bank confirming a recent charge at your usual grocery store, no action needed unless you don't recognize it.", "SAFE"),
    ("Hi, this is the pharmacy, your prescription is ready for pickup whenever you get a chance.", "SAFE"),
    ("This is a courtesy call from the airline, your flight tomorrow has been moved up by thirty minutes.", "SAFE"),
    ("Hi, I'm your delivery driver, I'm outside your building, just wanted to let you know.", "SAFE"),
    ("This is your insurance agent's office calling to schedule your annual policy review sometime next month.", "SAFE"),
]

"""Small labeled eval set: call-transcript snippet -> expected label (SCAM/SAFE).
Not exhaustive -- enough to give the search a real accuracy signal instead of
none. Expand this before citing accuracy numbers in the write-up; 16 examples
is a smoke-test-sized set, not a claim of statistical rigor.
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
    ("Hey, are we still on for lunch tomorrow at noon?", "SAFE"),
    ("This is the dentist's office confirming your cleaning appointment next Tuesday at 3pm.", "SAFE"),
    ("Can you pick up milk on your way home tonight?", "SAFE"),
    ("Hi, this is your doctor's office, your test results came back normal.", "SAFE"),
    ("Let's meet tomorrow afternoon at 3pm to discuss the project plan.", "SAFE"),
    ("This is the school calling, your kid forgot their lunch, just wanted you to know.", "SAFE"),
    ("Hey it's mom, just checking in, call me back when you get a chance.", "SAFE"),
    ("This is the plumber, I'll be there Thursday morning between nine and eleven.", "SAFE"),
]

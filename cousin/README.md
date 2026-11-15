# Cousin Heaven — server setup (DigitalOcean)

1. Create (or use) an Ubuntu droplet. Open its **Console** in the DigitalOcean website.
2. Run:
   ```
   git clone https://github.com/mazedcu/Homedesign.git
   cd Homedesign/cousin/server
   sudo ./setup.sh
   ```
3. Answer the questions (domain optional, family code, parent name, 4-digit PIN).
4. When it prints the address, open it in a browser to test, then enter the same address in the Cousin Heaven Android app on first launch.

**Update later:** `cd Homedesign && git pull && cd cousin/server && docker compose up -d --build`
**Backup:** copy the `cousin/server/data` folder (database + photos).
**Notes:** with no domain the app runs over plain http (fine for family use). With a domain, HTTPS is automatic. Parents sign in with the admin name/PIN and can remove members, reset PINs, change the family code and clear chat from the profile sheet.

# NextJS Frontend

This is the sole web interface for GPT Researcher. It runs separately from the FastAPI API and provides an intuitive interface for automated research.

#### Demo
<iframe height="400" width="700" src="https://github.com/user-attachments/assets/092e9e71-7e27-475d-8c4f-9dddd28934a3" frameborder="0" allow="autoplay; encrypted-media" allowfullscreen></iframe>

View an in-depth Product Tutorial here: [GPT-Researcher Frontend Tutorial](https://www.youtube.com/watch?v=hIZqA6lPusk)


## NextJS Frontend App

The React app is located in `frontend/nextjs`.

It comes with loads of added features, such as: 
 - a drag-n-drop user interface for uploading and deleting files to be used as local documents by GPTResearcher.
 - a GUI for setting your GPTR environment variables.
 - the ability to trigger the multi_agents flow via the Backend Module or Langgraph Cloud Host (currently in closed beta).
 - stability fixes
 - and more coming soon!

### Run the NextJS React App with Docker

> **Step 1** - [Install Docker](https://docs.gptr.dev/docs/gpt-researcher/getting-started/getting-started-with-docker)

> **Step 2** - Clone the '.env.example' file, add your API Keys to the cloned file and save the file as '.env'

> **Step 3** - Within the docker-compose file comment out services that you don't want to run with Docker.

```bash
docker compose up --build
```

If that doesn't work, try running it without the dash:
```bash
docker compose up --build
```

> **Step 4** - By default, if you haven't uncommented anything in your docker-compose file, this flow will start 2 processes:
 - the Python server running on localhost:8000
 - the React app running on localhost:3000

Visit localhost:3000 on any browser and enjoy researching!

Docker Compose reads `NEXT_PUBLIC_GPTR_API_URL` from the root `.env` file. Set it there if the GPTR API server does not run on localhost:8000.

For example:
```
NEXT_PUBLIC_GPTR_API_URL=https://app.gptr.dev
```

Or: 
```
NEXT_PUBLIC_GPTR_API_URL=http://localhost:7000
```

## Running NextJS Frontend via CLI

A more robust solution with enhanced features and performance.

#### Prerequisites
- Node.js (v18.17.0 recommended)
- npm

#### Setup and Running

1. Navigate to NextJS directory:
   ```
   cd frontend/nextjs
   ```

2. Set up Node.js:
   ```
   nvm install 18.17.0
   nvm use v18.17.0
   ```

3. Install dependencies:
   ```
   npm install --legacy-peer-deps
   ```

4. Configure the backend URL in `frontend/nextjs/.env.local`:
   ```
   NEXT_PUBLIC_GPTR_API_URL=http://127.0.0.1:8000
   ```

5. Start development server:
   ```
   npm run dev
   ```

6. Access at `http://localhost:3000`

The FastAPI backend must be running on port 8000. Its root path is a JSON status endpoint; it does not serve or redirect to the frontend.


### Adding Google Analytics

To add Google Analytics to your NextJS frontend, add the following to `frontend/nextjs/.env.local` for direct development or the root `.env` for Docker Compose:

```
NEXT_PUBLIC_GA_MEASUREMENT_ID="G-G2YVXKHJNZ"
```

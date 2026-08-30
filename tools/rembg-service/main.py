from fastapi import FastAPI, UploadFile, File, HTTPException, Response
from rembg import remove

app = FastAPI()


@app.get("/health")
def health():
    return {"status": "ok", "service": "rembg"}


@app.post("/remove")
async def remove_background(image: UploadFile = File(...)):
    try:
        input_bytes = await image.read()
        output_bytes = remove(input_bytes)
        return Response(content=output_bytes, media_type="image/png")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"rembg failed: {type(e).__name__}")
